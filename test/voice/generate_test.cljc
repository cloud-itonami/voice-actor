(ns voice.generate-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [voice.generate :as generate]))

(def persona {:tags ["network-isekai"]})

(deftest round-candidates-is-pure-and-reproducible
  (is (= (generate/round-candidates persona 3 3) (generate/round-candidates persona 3 3))))

(deftest round-candidates-count-matches-k
  (is (= 5 (count (generate/round-candidates persona 0 5)))))

(deftest round-candidates-ids-are-unique-within-a-round
  (let [cs (generate/round-candidates persona 2 4)]
    (is (= 4 (count (distinct (map :candidate/id cs)))))))

(deftest round-candidates-prompt-is-a-natural-sentence-not-a-descriptor-list
  (testing "the :prompt is the actual line to be spoken — one sentence, ending in
  terminal punctuation, and never the raw picked gene descriptor verbatim
  (unlike illust's comma-joined image prompt, a comma-joined descriptor list
  isn't a sentence a narrator would ever say)"
    (doseq [{:keys [prompt gene]} (generate/round-candidates persona 0 3)]
      (is (re-find #"[.!?]$" (str/trim prompt)))
      (is (not (str/includes? prompt (:line-type gene))))
      (is (not (str/includes? prompt (:delivery gene))))
      (is (not (str/includes? prompt (:register gene)))))))

(deftest different-rounds-vary-the-gene-selection
  (let [r0 (map :gene (generate/round-candidates persona 0 3))
        r1 (map :gene (generate/round-candidates persona 1 3))]
    (is (not= r0 r1))))

(deftest build-line-composes-one-sentence-with-delivery-aside
  (is (= "Hey, didn't expect to see you out this early — no rush."
         (generate/build-line "a warm NPC greeting" "calm and unhurried" "young adult, casual"))))

(deftest build-line-formal-clipped-delivery-adds-no-aside
  (is (= "Good to see you here."
         (generate/build-line "a warm NPC greeting" "formal and clipped" "neutral, plain"))))
