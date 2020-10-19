(ns fluree.db.ledger.consensus.raft
  (:require [fluree.raft :as raft]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [fluree.db.ledger.storage.filestore :as filestore]
            [fluree.db.storage.core :as storage]
            [fluree.db.serde.avro :as avro]
            [fluree.db.event-bus :as event-bus]
            [fluree.db.ledger.consensus.tcp :as ftcp]
            [fluree.db.util.async :refer [<? go-try]]
            [fluree.db.ledger.txgroup.txgroup-proto :as txproto :refer [TxGroup]]
            [fluree.db.ledger.consensus.update-state :as update-state]
            [fluree.db.ledger.txgroup.monitor :as group-monitor]
            [fluree.db.ledger.consensus.dbsync2 :as dbsync2]
            [fluree.crypto :as crypto])
  (:import (java.util UUID)
           (java.io File)))


(defn snapshot-xfer
  "Transfers snapshot from this server as leader, to a follower.
  Will be called with two arguments, snapshot id and part number.
  Initial call will be for part 1, and subsequent calls, if necessary,
  will be for each successive part.

  Must return a snapshot with the following fields
  :parts - how many parts total
  :data - snapshot data

  If multiple parts are returned, additional requests for each part will be
  requested. A snapshot should be broken into multiple parts if it is larger than
  the amount of data you want to push across the network at once."
  [path]
  (fn [id part]
    ;; in this example we do everything in one part, regardless of snapshot size
    (let [file (io/file path (str id ".snapshot"))
          ba   (byte-array (.length file))
          is   (io/input-stream file)]
      (.read is ba)
      (.close is)
      {:parts 1
       :data  ba})))


(defn snapshot-installer
  "Installs a new snapshot being sent from a different server.
  Blocking until write succeeds. An error will stop RAFT entirely.

  If snapshot-part = 1, should first delete any existing file if it exists (possible to have historic partial snapshot lingering).

  As soon as final part write succeeds, can safely garbage collect any old snapshots on disk except the most recent one."
  [path]
  (fn [snapshot-map]
    (let [{:keys [leader-id snapshot-term snapshot-index snapshot-part snapshot-parts snapshot-data]} snapshot-map
          file (io/file path (str snapshot-index ".snapshot"))]

      (when (= 1 snapshot-part)
        ;; delete any old file if exists
        (io/make-parents file)
        (io/delete-file file true))

      (with-open [out (io/output-stream file :append true)]
        (.write out ^bytes snapshot-data)))))


(defn snapshot-reify
  "Reifies a snapshot, should populate whatever data is needed into an initialized state machine
  that is used for raft.

  Called with snapshot-id to reify, which corresponds to the commit index the snapshot was taken.
  Should throw if snapshot not found, or unable to parse. This will stop raft."
  [path state-atom]
  (fn [snapshot-id]
    (try
      (let [file  (io/file path (str snapshot-id ".snapshot"))
            state (nippy/thaw-from-file file)]
        (reset! state-atom state))
      (catch Exception e (log/error e "Error reifying snapshot: " snapshot-id)))))


(defn- return-snapshot-id
  "Takes java file and returns log id (typically same as start index)
  from the file name as a long integer."
  [^File file]
  (when-let [match (re-find #"^([0-9]+)\.snapshot$" (.getName file))]
    (Long/parseLong (second match))))


(defn- purge-snapshots
  [path max-snapshots]
  (let [rm-snapshots (some->> (file-seq (clojure.java.io/file path))
                              (filter #(.isFile ^File %))
                              (keep return-snapshot-id)
                              (sort >)
                              (drop max-snapshots))]
    (when (not-empty rm-snapshots)
      (log/info "Removing snapshots: " rm-snapshots))
    (doseq [snapshot rm-snapshots]
      (let [file (io/file path (str snapshot ".snapshot"))]
        (io/delete-file file true)))))


(defn snapshot-writer
  "Blocking until write succeeds. An error will stop RAFT entirely."
  [path state-atom]
  (fn [index callback]
    (log/info "Ledger group snapshot write triggered for index: " index)
    (let [start-time    (System/currentTimeMillis)
          state         @state-atom
          file          (io/file path (str index ".snapshot"))
          max-snapshots 6]
      (io/make-parents file)
      (future
        (try (nippy/freeze-to-file file state)
             (catch Exception e (log/error e "Error writing snapshot index: " index)))
        (log/info (format "Ledger group snapshot completed for index %s in %s milliseconds."
                          index (- (System/currentTimeMillis) start-time)))
        (callback)
        (purge-snapshots path max-snapshots)))))


;; Holds state change functions that are registered
(def state-change-fn-atom (atom {}))


(defn register-state-change-fn
  "Registers function to be called with every state monitor change. id provided is used to un-register function
  and is otherwise opaque to the system."
  [id f]
  (swap! state-change-fn-atom assoc id f))

(defn unregister-state-change-fn
  [id]
  (swap! state-change-fn-atom dissoc id))


(defn unregister-all-state-change-fn
  []
  (reset! state-change-fn-atom {}))


(defn state-machine
  [server-id state-atom storage-read storage-write]
  (fn [command raft-state]
    (let [op     (first command)
          result (case op

                   ;; new block is a special case as it requires a couple atomic transactions
                   ;; a new block is accepted only if
                   ;; (a) the submission-server is currently the worker for the network
                   ;; (b) the block-id is exactly one block increment from the previous block
                   ;; if it contains a command-type of :new-db, we also establish a new db record
                   :new-block (let [[_ network dbid block-map submission-server] command
                                    {:keys [block txns cmd-types]} block-map
                                    txids           (keys txns)
                                    file-key        (storage/ledger-block-key network dbid block)
                                    current-block   (get-in @state-atom [:networks network :dbs dbid :block])
                                    is-next-block?  (if current-block
                                                      (= block (inc current-block))
                                                      (= 1 block))
                                    server-allowed? (= submission-server
                                                       (get-in @state-atom [:_work :networks network]))]
                                ;; if :new-db in cmd-types, then register new-db
                                (when (cmd-types :new-db)
                                  (update-state/register-new-dbs txns state-atom block-map))

                                (if (and is-next-block? server-allowed?)
                                  (do
                                    ;; write out block data - todo: ensure raft shutdown happens successfully if write fails
                                    (storage-write file-key (avro/serialize-block block-map))

                                    ;; update current block, and remove txids from queue
                                    (swap! state-atom
                                           (fn [state] (update-state/update-ledger-block network dbid txids state block)))

                                    (log/info (str network "/" dbid " new-block " {:block         block
                                                                                   :txns          txids
                                                                                   :server        submission-server
                                                                                   :network-queue (count (get-in @state-atom [:cmd-queue network]))}))

                                    ;; publish new-block event
                                    (event-bus/publish :block [network dbid] block-map)
                                    ;; return success!
                                    true)
                                  (do
                                    (swap! state-atom
                                           (fn [state]
                                             (reduce (fn [s txid] (update-state/dissoc-in s [:cmd-queue network txid])) state txids)))

                                    (ex-info (str " --------------- BLOCK REJECTED! "
                                                  (if (not is-next-block?)
                                                    (str "Blocks out of order. Block " block " should be one more than current block: " current-block)
                                                    (str "Server: " submission-server " is not registered as current worker for this network: " network
                                                         ". That server is: " (get-in @state-atom [:_work :networks network])))
                                                  (pr-str {:server-allowed server-allowed?
                                                           :is-next-block? is-next-block?
                                                           :command        command
                                                           :state-dump     @state-atom}))
                                             {:error  :db/invalid-block
                                              :status 500}))))


                   ;; stages a new db to be created
                   :new-db (update-state/stage-new-db command state-atom)

                   :delete-db (update-state/delete-db command state-atom)

                   :initialized-db (update-state/initialized-db command state-atom)

                   :new-index (update-state/new-index command state-atom)

                   :lowercase-all-names (update-state/lowercase-all-names command state-atom)

                   :assoc-in (update-state/assoc-in* command state-atom)

                   ;; worker assignments are a little different in that they organize the key-seq
                   ;; both prepended by the server-id (for easy lookup of work based on server-id)
                   ;; and also at the end of the key-seq (for easy lookup of worker(s) for given resource(s))
                   ;; all worker data is stored under the :_worker key
                   :worker-assign (let [[_ ks worker-id] command
                                        unassign? (nil? worker-id)
                                        work-ks   (into [:_work] ks)
                                        worker-ks (into [:_worker worker-id] ks)]
                                    (swap! state-atom
                                           (fn [state]
                                             (let [existing-worker (get-in state work-ks)]
                                               (if unassign?
                                                 (-> state
                                                     (update-state/dissoc-in work-ks)
                                                     (update-state/dissoc-in worker-ks))
                                                 (-> (if existing-worker
                                                       (update-state/dissoc-in state (into [:_worker existing-worker] ks))
                                                       state)
                                                     (assoc-in work-ks worker-id)
                                                     (assoc-in worker-ks (System/currentTimeMillis)))))))
                                    true)

                   :get-in (update-state/get-in* command state-atom)

                   ;; Returns true if there was an existing value removed, else false.
                   :dissoc-in (update-state/assoc-in* command state-atom)

                   ;; acquires lease, stored at specified ks (a more elaborate cas). Uses local clock
                   ;; to help combat clock skew. Will only allow a single lease at specified ks.
                   ;; returns true if provided id has the lease, false if other has the lease
                   :lease (let [[_ ks id expire-ms] command
                                epoch-ms     (System/currentTimeMillis)
                                expire-epoch (+ epoch-ms expire-ms)
                                new-lease    {:id id :expire expire-epoch}
                                new-state    (swap! state-atom update-in ks
                                                    (fn [current-lease]
                                                      (cond
                                                        ;; no lease, or renewal from current lease holder
                                                        (or (nil? current-lease) (= (:id current-lease) id))
                                                        new-lease

                                                        ;; a different id has the lease, not expired
                                                        (<= epoch-ms (:expire current-lease))
                                                        current-lease

                                                        ;; a different id has the lease, expired
                                                        :else
                                                        new-lease)))]
                            ;; true if have the lease
                            (= id (:id (get-in new-state ks))))

                   ;; releases lease if id is the current lease holder, or no lease exists. Returns true as operation always successful.
                   :lease-release (let [[_ ks id] command]
                                    (swap! state-atom
                                           (fn [state]
                                             (let [release? (or (nil? (get-in state ks))
                                                                (= id (:id (get-in state ks))))]
                                               (if release?
                                                 (update-state/dissoc-in state ks)
                                                 state))))
                                    true)

                   ;; Will replace current val at key sequence only if existing val is = compare value at compare key sequence.
                   ;; Returns true if value updated.
                   :cas-in (update-state/cas-in command state-atom)

                   ;; Will replace current val only if existing val is < proposed val. Returns true if value updated.
                   :max-in (update-state/max-in command state-atom)

                   ;; only used for local file-system storage (not centralized)
                   ;; does not block, always returns true.
                   ;; TODO - implement logic to retry writes if ultimately not successful (by re-requesting index ID from raft)
                   :storage-write (let [[_ key bytes] command]
                                    (future (storage-write key bytes))
                                    true)

                   ;; used only for a fully synchronized read - most reads should happen in local state
                   :storage-read (let [[_ key] command]
                                   (storage-read key)))]
      ;; call any registered state change functions
      (when-let [state-change-fns (vals @state-change-fn-atom)]
        (doseq [f state-change-fns]
          (try
            (f {:command command :result result})
            (catch Exception e (log/error e "State change function error.")))))
      result)))


;; map of request-ids to a response channel that will contain the response eventually
(def pending-responses (atom {}))


(defn send-rpc
  "Sends rpc call to specified server.
  Includes a resp-chan that will eventually contain a response.

  Returns true if successful, else will return an exception if
  connection doesn't exist (not established, or closed)."
  [raft server operation data callback]
  (let [this-server (:this-server raft)
        msg-id      (str (UUID/randomUUID))
        header      {:op     operation
                     :from   this-server
                     :to     server
                     :msg-id msg-id}]
    (when (fn? callback)
      (swap! pending-responses assoc msg-id callback))
    (let [success? (ftcp/send-rpc this-server server header data)]
      (if success?
        (log/trace "send-rpc success:" {:op operation :data data :header header})
        (do
          (swap! pending-responses dissoc msg-id)
          (log/debug "Connection to" server "is closed, unable to send rpc. " (pr-str header)))))))


(defn message-consume
  "Function used to consume inbound server messages.

  client-id should be an approved client-id from the initial
  client negotiation process, can be can used to validate incoming
  messages are labeled as coming from the correct client."
  [raft key-storage-read-fn conn message]
  (try
    (let [message'  (nippy/thaw message)
          [header data] message'
          {:keys [op msg-id]} header
          response? (str/ends-with? (name op) "-response")
          {:keys [write-chan]} conn]
      (if response?
        (let [callback (get @pending-responses msg-id)]
          (when (fn? callback)
            (swap! pending-responses dissoc msg-id)
            (callback data)))
        (let [resp-header (assoc header :op (keyword (str (name op) "-response"))
                                        :to (:from header)
                                        :from (:to header))
              callback    (fn [x]
                            (ftcp/send-message write-chan resp-header x))]
          (case op
            :storage-read
            (let [file-key data]
              (log/debug "Storage read for key: " file-key)
              (async/go
                (-> (storage/storage-read {:storage-read key-storage-read-fn} file-key)
                    (async/<!)
                    (callback))))

            :new-command
            (let [{:keys [id entry]} data
                  command (raft/map->RaftCommand {:entry entry
                                                  :id    id})]
              (log/debug "Raft - new command:" (pr-str data))
              (raft/new-command raft command callback))

            ;; else
            (raft/invoke-rpc raft op data callback)))))
    (catch Exception e (log/error e "Error consuming new message! Ignoring."))))


;; start with a default state when no other state is present (initialization)
;; useful for setting a base 'version' in state
(def default-state {:version 3})


(defn start-instance
  [raft-config]
  (let [{:keys [port this-server log-directory entries-max log-history
                storage-read storage-write private-keys open-api]} raft-config
        event-chan             (async/chan)
        command-chan           (async/chan)
        state-machine-atom     (atom default-state)
        log-directory          (or log-directory (str "raftlog/" (name this-server) "/"))
        raft-config*           (assoc raft-config :event-chan event-chan
                                                  :command-chan command-chan
                                                  :send-rpc-fn send-rpc
                                                  :log-history log-history
                                                  :log-directory log-directory
                                                  :entries-max (or entries-max 50)
                                                  :state-machine (state-machine this-server state-machine-atom storage-read storage-write)
                                                  :snapshot-write (snapshot-writer (str log-directory "snapshots/") state-machine-atom)
                                                  :snapshot-reify (snapshot-reify (str log-directory "snapshots/") state-machine-atom)
                                                  :snapshot-xfer (snapshot-xfer (str log-directory "snapshots/"))
                                                  :snapshot-install (snapshot-installer (str log-directory "snapshots/")))
        _                      (log/debug "Starting Raft with config:" (pr-str raft-config*))
        raft                   (raft/start raft-config*)
        client-message-handler (partial message-consume raft storage-read)
        new-client-handler     (fn [client]
                                 (ftcp/monitor-remote-connection this-server client client-message-handler nil))
        ;; both starts server and returns a shutdown function
        server-shutdown-fn     (ftcp/start-tcp-server port new-client-handler)]

    {:raft            raft
     :state-atom      state-machine-atom
     :port            port
     :server-shutdown server-shutdown-fn
     :this-server     this-server
     :event-chan      event-chan
     :command-chan    command-chan
     :private-keys    private-keys
     :open-api        open-api}))



(defn view-raft-state
  "Returns current raft state to callback."
  ([raft] (view-raft-state raft (fn [x] (clojure.pprint/pprint (dissoc x :config)))))
  ([raft callback]
   (raft/view-raft-state (:raft raft) callback)))


(defn view-raft-state-async
  "Returns current raft state as a core async channel."
  [raft]
  (let [resp-chan (async/promise-chan)]
    (raft/view-raft-state (:raft raft)
                          (fn [rs]
                            (async/put! resp-chan rs)
                            (async/close! resp-chan)))
    resp-chan))


(defn monitor-raft
  "Monitor raft events and state for debugging"
  ([raft] (monitor-raft raft (fn [x] (clojure.pprint/pprint x))))
  ([raft callback]
   (raft/monitor-raft (:raft raft) callback)))


(defn monitor-raft-stop
  "Stops current raft monitor"
  [raft]
  (raft/monitor-raft (:raft raft) nil))


(defn leader-async
  "Returns leader as a core async channel once available.
  Default timeout supplied, or specify one."
  ([raft] (leader-async raft 60000))
  ([raft timeout]
   (let [timeout-time (+ (System/currentTimeMillis) timeout)]
     (async/go-loop [retries 0]
       (let [resp-chan (async/promise-chan)
             _         (view-raft-state raft (fn [state]
                                               (if-let [leader (:leader state)]
                                                 (async/put! resp-chan leader)
                                                 (async/close! resp-chan))))
             resp      (async/<! resp-chan)]
         (cond
           resp resp

           (> (System/currentTimeMillis) timeout-time)
           (ex-info (format "Leader not yet established and timeout of %s reached. Polled raft state %s times." timeout retries)
                    {:status 400 :error :db/leader-timeout})

           :else
           (do
             (async/<! (async/timeout 100))
             (recur (inc retries)))))))))


(defn is-leader?-async
  [raft]
  (async/go
    (let [leader (async/<! (leader-async raft))]
      (if (instance? Throwable leader)
        leader
        (= (:this-server raft) leader)))))


(defn is-leader?
  [raft]
  (let [leader? (async/<!! (is-leader?-async raft))]
    (if (instance? Throwable leader?)
      (throw leader?)
      leader?)))

(defn state
  [raft]
  (let [state (async/<!! (view-raft-state-async raft))]
    (if (instance? Throwable state)
      (throw state)
      state)))


;; TODO configurable timeout
(defn new-entry-async
  "Sends a command to the leader. If no callback provided, returns a core async promise channel
  that will eventually contain a response."
  ([group entry] (new-entry-async group entry 5000))
  ([group entry timeout-ms]
   (let [resp-chan (async/promise-chan)
         callback  (fn [resp]
                     (if (nil? resp)
                       (async/close! resp-chan)
                       (async/put! resp-chan resp)))]
     (new-entry-async group entry timeout-ms callback)
     resp-chan))
  ([group entry timeout-ms callback]
   (go-try (let [raft'  (:raft group)
                 leader (async/<! (leader-async group))]
             (if (= (:this-server raft') leader)
               (raft/new-entry raft' entry callback timeout-ms)
               (let [id           (str (UUID/randomUUID))
                     command-data {:id id :entry entry}]
                 ;; since not leader, register entry id locally and will receive callback when committed to state machine
                 (raft/register-callback raft' id timeout-ms callback)
                 ;; send command to leader
                 (send-rpc raft' leader :new-command command-data nil)))))))

(defn add-server-async
  "Sends a command to the leader. If no callback provided, returns a core async promise channel
  that will eventually contain a response."
  ([group newServer] (add-server-async group newServer 5000))
  ([group newServer timeout-ms]
   (let [resp-chan (async/promise-chan)
         callback  (fn [resp]
                     (if (nil? resp)
                       (async/close! resp-chan)
                       (async/put! resp-chan resp)))]
     (add-server-async group newServer timeout-ms callback)
     resp-chan))
  ([group newServer timeout-ms callback]
   (go-try (let [raft'  (:raft group)
                 leader (async/<! (leader-async group))
                 id     (str (UUID/randomUUID))]
             (if (= (:this-server raft') leader)
               (let [command-chan (-> group :command-chan)]
                 (async/put! command-chan [:add-server [id newServer] callback]))
               (do (raft/register-callback raft' id timeout-ms callback)
                   ;; send command to leader
                   (send-rpc raft' leader :add-server [id newServer] nil)))))))

(defn remove-server-async
  "Sends a command to the leader. If no callback provided, returns a core async promise channel
  that will eventually contain a response."
  ([group server] (remove-server-async group server 5000))
  ([group server timeout-ms]
   (let [resp-chan (async/promise-chan)
         callback  (fn [resp]
                     (if (nil? resp)
                       (async/close! resp-chan)
                       (async/put! resp-chan resp)))]
     (remove-server-async group server timeout-ms callback)
     resp-chan))
  ([group server timeout-ms callback]
   (go-try (let [raft'  (:raft group)
                 leader (async/<! (leader-async group))
                 id     (str (UUID/randomUUID))]
             (if (= (:this-server raft') leader)
               (let [command-chan (-> group :command-chan)]
                 (async/put! command-chan [:remove-server [id server] callback]))
               (do (raft/register-callback raft' id timeout-ms callback)
                   ;; send command to leader
                   (send-rpc raft' leader :remove-server [id server] nil)))))))

(defn local-state
  "Returns local, current state from state machine"
  [raft]
  @(:state-atom raft))


(defn acquire-lease-async
  "Acquires a lease as specified key sequence in state map using provided id for specified ms.
  Must re-acquire before expiration. Lease is not automatically removed, but using 'leased?' function
  will look at lease time, and expired leases will allow new ids to acquire lease.

  Returns true if lease acquired, false otherwise."
  [raft ks id expire-ms]
  (let [command [:lease ks id expire-ms]]
    (new-entry-async raft command)))


(defn acquire-lease
  [raft ks id expire-ms]
  (async/<!! (acquire-lease-async raft ks id expire-ms)))


(defn release-lease-async
  [raft ks id]
  (let [command [:lease-release ks id]]
    (new-entry-async raft command)))


(defn lessor
  "Returns id of lease holder at specified key-seq if a lease exists and not expired, else nil."
  [raft ks]
  (let [lease (txproto/kv-get-in raft ks)]
    (if (or (nil? lease) (< (:expire lease) (System/currentTimeMillis)))
      nil
      (:id lease))))


(defn storage-write-async
  "Performs a fully consistent storage write."
  [raft k data]
  (let [command [:storage-write k data]]
    (new-entry-async raft command)))


(defn storage-write
  "Performs a fully consistent storage write."
  [raft k data]
  (async/<!! (storage-write-async raft k data)))


(defn storage-read-async*
  "Performs a fully consistent storage-read of provided key."
  [raft key]
  (let [command [:storage-read key]]
    (new-entry-async raft command)))


(defn storage-read*
  "Performs a fully consistent storage-read of provided key."
  [raft key]
  (async/<!! (storage-read-async* raft key)))


(defn server-active?
  "Returns true if server has a currently active lease."
  [raft server-id]
  (let [lease (txproto/kv-get-in raft [:leases :servers server-id])]
    (if lease
      (>= (:expire lease) (System/currentTimeMillis))
      false)))



(defn index-fully-committed?
  "Returns a core async channel that will eventually return the index/commit once they are both equal.

  This helps when building state machine at startup if there is no pre-existing leader.
  In this case we can know when all of this historical entries that might have been committed
  previously are re-committed.

  If leader-only? boolean flag is true, will close the channel if not leader as opposed to returning result.
  Either way we still wait until commit is fully updated in case there is a leader change during that process.

  Note if massively high volume, could be that commit and index are never equal. Not likely but in
  this case we eventually return an exception if we retry 10000 times just to have an upper bounds.
  Exception doesn't throw, be sure to check for it."
  ([raft] (index-fully-committed? raft false))
  ([raft leader-only?]
   (async/go-loop [retries 0]
     (let [rs (async/<! (view-raft-state-async raft))
           {:keys [commit index status latest-index]} rs]
       (cond

         (>= index latest-index)
         (if (or (not leader-only?)
                 (= :leader status))
           commit
           nil)


         (> retries 10000)
         (ex-info (str "Raft index-fully-committed? loop tried 10000 times without a change. Latest state: " (pr-str rs))
                  {:status 500 :error :db/unexpected-error})

         :else
         (do
           (async/<! (async/timeout 100))
           (recur (inc retries))))))))

(defn register-server-lease-async
  "Registers a server as available with provided lease expiration.
  Leases need to be continuously renewed else they will expire.

  Don't register unless you are ready to become a ledger (i.e. have synced all index files).

  Returns true if lease secured, false otherwise."
  [raft expire-ms]
  (let [this-server (:this-server (:raft raft))]
    (acquire-lease-async raft [:leases :servers this-server] this-server expire-ms)))


(defn raft-start-up
  [group conn system* shutdown join?]
  (async/go
    (try (let [status           (async/<!! (:raft-initialized group))
               ;; status is :leader or :follower once initialized
               ;; get caught up and fully committed
               fully-committed? (async/<! (index-fully-committed? group true))]
           (when (instance? Throwable fully-committed?)
             (log/error fully-committed? "Exception when initializing raft. Shutting down.")
             (shutdown system*)
             (System/exit 1))

           ;; do an initial file sync... the committed raft may contain blocks that end up leaving gaps
           (let [sync-finished? (async/<! (dbsync2/consistency-full-check conn (:other-servers (:raft group))))
                 ;; then check the full-text index is up-to-date
                 storage-dir    (-> conn :meta :storage-directory)
                 group-raft     (:group conn)
                 current-state  @(:state-atom group-raft)
                 ledgers-info   (txproto/all-ledger-block current-state)
                 _              (async/<! (dbsync2/check-full-text-synced conn storage-dir ledgers-info))]
             (if (instance? Exception sync-finished?)
               (dbsync2/terminate! conn
                                   "Terminating due to file syncing error, unable to sync required files with other servers."
                                   sync-finished?)
               (log/debug "All database files synchronized.")))

           ;; register on the network
           (async/<! (register-server-lease-async group 5000))


           (when (async/<! (is-leader?-async group))
             (when (empty? (txproto/get-shared-private-key group))
               (log/info "Brand new Fluree instance, establishing default shared private key.")
               ;; TODO - check environment to see if a private key was supplied
               (let [private-key (or (:tx-private-key conn)
                                     (:private (crypto/generate-key-pair)))]
                 (txproto/set-shared-private-key (:group conn) private-key))))


           ;; monitor state changes to kick of transactions for any queues
           (register-state-change-fn (str (UUID/randomUUID))
                                     (partial group-monitor/state-updates-monitor system*))

           ;; in case we are responsible for networks but some exist in current queue, kick them off
           (group-monitor/kick-all-assigned-networks-with-queue conn)

           ;; create a loop to keep this server registered
           (loop []
             (let [;; pause 3 seconds
                   _           (async/<! (async/timeout 3000))
                   ;; TODO need to stop loop if server stopped
                   registered? (async/<! (register-server-lease-async group 5000))
                   leader?     (async/<! (is-leader?-async group))]

               ;; if leader, re-check worker distribute to ensure nothing is stuck
               (when (and leader? (true? registered?))
                 (group-monitor/redistribute-workers group)))

             (recur)))

         (catch Exception e
           (log/warn "Error during raft initialization. Shutting down system")
           (log/error e)
           (shutdown system*)
           (System/exit 1)))))


(defrecord RaftGroup [state-atom event-chan command-chan server this-server port close raft raft-initialized open-api private-keys]
  TxGroup
  (-add-server-async [group server] (add-server-async group server))
  (-remove-server-async [group server] (remove-server-async group server))
  (-new-entry-async [group entry] (new-entry-async group entry))
  (-local-state [group] (local-state group))
  (-state [group] (state group))
  (-is-leader? [group] (is-leader? group))
  (-active-servers [group] (let [server-map   (txproto/kv-get-in group [:leases :servers])
                                 current-time (System/currentTimeMillis)]
                             (reduce-kv (fn [acc server lease-data]
                                          (if (>= (:expire lease-data) current-time)
                                            (conj acc server)
                                            acc))
                                        #{} server-map)))
  (-start-up-activities [group conn system shutdown join?] (raft-start-up group conn system shutdown join?)))


(defn launch-raft-server
  [server-configs this-server raft-configs]
  (let [join?                 (:join? raft-configs)
        server-duplicates?    (not= (count server-configs) (count (into #{} (map :server-id server-configs))))
        _                     (when server-duplicates?
                                (throw (ex-info (str "There appear to be duplicates in the group servers configuration: "
                                                     (pr-str server-configs))
                                                {:status 400 :error :db/invalid-configuration})))
        this-server-cfg       (some #(when (= this-server (:server-id %)) %) server-configs)
        _                     (when-not this-server-cfg
                                (throw (ex-info (str "This server: " (pr-str this-server) " has to be included in the group
                                server configuration." (pr-str server-configs))
                                                {:status 400 :error :db/invalid-configuration})))
        raft-servers          (->> server-configs           ;; ensure unique
                                   (mapv :server-id server-configs)
                                   (into #{})
                                   (into []))
        server-duplicates?    (not= (count server-configs) (count raft-servers))
        _                     (when server-duplicates?
                                (throw (ex-info (str "There appear to be duplicates in the group servers configuration: "
                                                     (pr-str server-configs))
                                                {:status 400 :error :db/invalid-configuration})))
        raft-initialized-chan (async/promise-chan)
        leader-change-fn      (:leader-change-fn raft-configs)
        leader-change-fn*     (fn [change-map]
                                (let [{:keys [new-raft-state old-raft-state]} change-map]
                                  (log/info "Ledger group leader change:" (dissoc change-map :key :new-raft-state :old-raft-state))
                                  (log/debug "Old raft state: \n" (pr-str old-raft-state) "\n"
                                             "New raft state: \n" (pr-str new-raft-state))
                                  (when (not (nil? new-raft-state))
                                    (cond (and join? (not (nil? (:leader new-raft-state)))
                                               (not= this-server (:leader new-raft-state)))
                                          (async/put! raft-initialized-chan :follower)

                                          join?
                                          true

                                          (= this-server (:leader new-raft-state))
                                          (async/put! raft-initialized-chan :leader)

                                          :else
                                          (async/put! raft-initialized-chan :follower)))
                                  (when (fn? leader-change-fn)
                                    (leader-change-fn change-map))))
        raft-instance         (start-instance (merge raft-configs
                                                     {:port             (:port this-server-cfg)
                                                      :servers          raft-servers
                                                      :this-server      this-server
                                                      :leader-change-fn leader-change-fn*}))
        close-fn              (fn []
                                ;; close raft
                                (raft/close (:raft raft-instance))
                                ;; Unregister state-change-fns
                                (unregister-all-state-change-fn)
                                ;; close any open connections
                                (ftcp/close-all-connections this-server)
                                ;; close tcp server
                                ((:server-shutdown raft-instance)))]

    ;; we need a single duplex connection to each server.
    ;; TODO - Need slightly more complicated handling. If a server joins, close, and tries to restart with join = false, will fail
    (if join?
      ;; If joining an existing network, connects to all other servers
      (let [connect-servers (filter #(not= (:server-id this-server) %) server-configs)
            handler-fn      (partial message-consume (:raft raft-instance) (:storage-read raft-configs))]
        (doseq [connect-to connect-servers]
          (ftcp/launch-client-connection this-server-cfg connect-to handler-fn)))

      ;; simple rule (for now) is we connect to servers whose id is > (lexical sort) than our own
      (let [connect-servers (filter #(> 0 (compare this-server (:server-id %))) server-configs)
            handler-fn      (partial message-consume (:raft raft-instance) (:storage-read raft-configs))]
        (doseq [connect-to connect-servers]
          (ftcp/launch-client-connection this-server-cfg connect-to handler-fn))))


    (-> (assoc raft-instance :raft-initialized raft-initialized-chan
                             :close close-fn)
        map->RaftGroup)))