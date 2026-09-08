(ns voice.loop
  "The durable outer loop (ADR-2607123000 §3) — NOT a StateGraph. murakumo
  generation jobs are async (:queued -> :running -> :done|:failed over
  minutes), so a tick either (a) submits a fresh round when under budget and
  nothing is pending, or (b) polls a pending round and, once settled, runs it
  through voice.cosci -> voice.governor -> commit+publish. `run!` reduces
  to repeated `tick!` calls; crash recovery is just re-running tick! against
  the same on-disk state/lease/ledger — every step here is safe to repeat.

  HONEST LIMIT: whether jobs this loop submits actually complete depends on a
  murakumo fleet worker being up and consuming the `gftd-murakumo` kotoba
  queue — see voice.murakumo's docstring."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [kotoba.lang.text :as str]
            [voice.persona :as persona]
            [voice.generate :as generate]
            [voice.murakumo :as murakumo]
            [voice.judge :as judge]
            [voice.cosci :as cosci]
            [voice.governor :as governor]
            [voice.datalad :as datalad]
            [voice.ledger :as ledger]
            [voice.publish :as publish]
            [voice.aozora :as aozora]))

(def lease-path ".voice/lease")
(def state-path ".voice/state.edn")
(def lease-stale-ms (* 30 60 1000))
(def round-timeout-ms (* 30 60 1000))
(def round-size 3)

(defn daily-budget []
  (or (some-> (System/getenv "ASSET_ACTOR_DAILY_BUDGET") parse-long) 8))

(defn- now-ms [] (System/currentTimeMillis))
(defn- today-str [] (str (java.time.LocalDate/now)))

;; ── lease ─────────────────────────────────────────────────────────────────

(defn acquire-lease!
  "false if another process holds a fresh lease; true (and writes the lease
  file) otherwise. Single-writer discipline (root CLAUDE.md's 'worktree per
  agent, never edit a shared checkout concurrently' applied to this loop)."
  []
  (let [f (io/file lease-path)]
    (io/make-parents f)
    (if (and (.exists f) (< (- (now-ms) (.lastModified f)) lease-stale-ms))
      false
      (do (spit f (str (now-ms))) true))))

(defn release-lease! [] (.delete (io/file lease-path)))

;; ── state ─────────────────────────────────────────────────────────────────

(def empty-state {:round 0 :day nil :submitted-today 0 :pending [] :elite-gene nil
                  :round-started-at nil})

(defn load-state []
  (let [f (io/file state-path)]
    (if (.exists f) (edn/read-string (slurp f)) empty-state)))

(defn save-state! [s] (io/make-parents state-path) (spit state-path (pr-str s)) s)

(defn- roll-day [{:keys [day] :as state}]
  (let [today (today-str)]
    (if (= day today) state (assoc state :day today :submitted-today 0))))

;; ── round submit ─────────────────────────────────────────────────────────

(defn submit-round!
  "Under budget, nothing pending -> generate + submit a fresh round. Returns
  the updated state (never throws on a single candidate's submit failure —
  logs to the ledger and continues with however many did submit)."
  [state]
  (let [p (persona/load-persona)
        n (:round state)
        k (min round-size (max 0 (- (daily-budget) (:submitted-today state))))
        candidates (generate/round-candidates p n k (:elite-gene state))
        submitted (keep (fn [c]
                          (try
                            (let [job (murakumo/submit! c)]
                              (ledger/append! {:t :submitted :round n
                                              :candidate-id (:candidate/id c)
                                              :job-id (:gen.job/id job)})
                              {:candidate c :job-id (:gen.job/id job)})
                            (catch Exception e
                              (ledger/append! {:t :submit-failed :round n
                                              :candidate-id (:candidate/id c)
                                              :reason (ex-message e)})
                              nil)))
                        candidates)]
    (save-state!
     (-> state
         (assoc :pending submitted)
         (update :submitted-today + (count submitted))
         (assoc :round-started-at (now-ms))))))

;; ── round poll + settle ──────────────────────────────────────────────────

(defn- fetch-result
  "One pending entry -> a voice.cosci/reflect-shaped result map, or nil if
  still in flight (:queued/:running and not yet timed out)."
  [expected-format timed-out? {:keys [candidate job-id]}]
  (let [job (try (murakumo/poll job-id) (catch Exception _ nil))]
    (cond
      (nil? job)
      (when timed-out? {:candidate candidate :status :failed :artifact-bytes nil
                        :format nil :expected-format expected-format :safety-flag false})

      (murakumo/done? job)
      (let [bytes (murakumo/fetch-artifact! job)
            fmt (some-> (first (:gen.job/artifacts job)) (str/split #"\.") last str/lower)]
        {:candidate candidate :status :done :artifact-bytes bytes
         :format fmt :expected-format expected-format
         :safety-flag (boolean (:gen.job/safety-flag job))})

      (murakumo/failed? job)
      {:candidate candidate :status :failed :artifact-bytes nil
       :format nil :expected-format expected-format :safety-flag false}

      timed-out?
      {:candidate candidate :status :failed :artifact-bytes nil
       :format nil :expected-format expected-format :safety-flag false}

      :else nil)))

(defn- all-settled? [state]
  (let [p (persona/load-persona)
        timed-out? (and (:round-started-at state)
                        (> (- (now-ms) (:round-started-at state)) round-timeout-ms))]
    (every? some? (mapv (partial fetch-result (:persona/format p) timed-out?) (:pending state)))))

(defn- asset-title [gene]
  (str/join ", " (remove nil? [(:line-type gene) (:register gene)])))

(defn settle-round!
  "All pending jobs are terminal (or the round timed out) -> run cosci,
  governor-check the winner, commit+publish if it passes, ledger everything,
  advance :round/:elite-gene, clear :pending. `publisher` defaults to the
  REAL aozora publisher (ADR-2607123000 — live by default); pass
  voice.publisher/mock-publisher in tests."
  ([state] (settle-round! state nil))
  ([state publisher]
   (let [p (persona/load-persona)
         n (:round state)
         timed-out? (and (:round-started-at state)
                         (> (- (now-ms) (:round-started-at state)) round-timeout-ms))
         results (mapv (partial fetch-result (:persona/format p) timed-out?) (:pending state))
         judged (into {} (keep (fn [{:keys [candidate status]}]
                                 (when (= status :done)
                                   (when-let [j (judge/score! candidate p)]
                                     [(:candidate/id candidate) j])))
                               results))
         {:keys [ranked evolution meta-review]} (cosci/run-round results judged n)
         winner (first ranked)]
     (doseq [r results]
       (when-not (= :done (:status r))
         (ledger/append! {:t :disqualified :round n
                          :candidate-id (:candidate/id (:candidate r))})))
     (doseq [r (rest ranked)]
       (ledger/append! {:t :not-selected :round n
                        :candidate-id (get-in r [:result :candidate :candidate/id])
                        :score (:score r)}))
     (when winner
       (let [{:keys [result score]} winner
             {:keys [candidate artifact-bytes format]} result
             job-id (:candidate/id candidate)
             asset {:id (str "voice-" (str/replace job-id #"[^a-zA-Z0-9-]" "-"))
                    :kind :asset :format format :artifact-bytes artifact-bytes
                    :title (asset-title (:gene candidate))
                    :license (:persona/license p) :tags (:persona/tags p)
                    :gen-job-id job-id :prompt (:prompt candidate)
                    :created (str (java.time.Instant/now))}
             violations (governor/violations asset)]
         (if (seq violations)
           (ledger/append! {:t :held :round n :candidate-id job-id :reason violations})
           (let [manifest (datalad/->isekai-manifest (assoc asset :kind (:persona/kind p)))
                 {:keys [asset-path manifest-path]} (datalad/write-asset! (assoc asset :manifest manifest))]
             (datalad/save+push! [asset-path manifest-path]
                                 (str "asset: round " n " winner " job-id " (score " score ")"))
             (let [pub (or publisher (aozora/aozora-publisher
                                       (merge (publish/json-opts)
                                              {:identity (publish/load-or-create-identity!)})))
                   published (publish/publish! pub (assoc asset :cid manifest-path))]
               (ledger/append! {:t :accepted :round n :candidate-id job-id :score score
                                :asset-path asset-path :published published}))))))
     (ledger/append! (assoc meta-review :t :meta-review))
     (save-state!
      (-> state
          (assoc :round (:next-round evolution))
          (assoc :elite-gene (:elite-gene evolution))
          (assoc :pending [])
          (assoc :round-started-at nil))))))

;; ── tick / run ───────────────────────────────────────────────────────────

(defn tick!
  "One unit of work. Returns a keyword describing what happened —
  :lease-held (skip, another process is ticking) / :submitted (fresh round
  sent) / :waiting (pending round, not yet settled) / :settled (round
  judged+committed-or-held) / :idle (nothing to do, budget exhausted)."
  ([] (tick! nil))
  ([publisher]
   (if-not (acquire-lease!)
     :lease-held
     (try
       (let [state (roll-day (load-state))]
         (cond
           (and (empty? (:pending state)) (< (:submitted-today state) (daily-budget)))
           (do (submit-round! state) :submitted)

           (seq (:pending state))
           (if (all-settled? state)
             (do (settle-round! state publisher) :settled)
             (do (save-state! state) :waiting))

           :else
           (do (save-state! state) :idle)))
       (finally (release-lease!))))))

(defn run-forever!
  "Repeat tick! forever with `interval-ms` sleeps between ticks (default 10
  min). Intended for a long-lived process (launchd/systemd on a fleet node);
  `clojure -M:run tick` (single tick, cron-friendly) is the alternative — see
  voice.main."
  ([] (run-forever! (* 10 60 1000)))
  ([interval-ms]
   (loop []
     (let [result (tick!)]
       (println (str (java.time.Instant/now) " tick -> " result)))
     (Thread/sleep ^long interval-ms)
     (recur))))

(defn status []
  {:state (load-state) :ledger-tail (take-last 10 (ledger/read-all))})
