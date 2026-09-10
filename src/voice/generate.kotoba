(ns voice.generate
  "Pure candidate builder for one co-scientist round (ADR-2607123000 §2/§3).

  Same 'closed hypothesis pool, no LLM in Generation' discipline
  cloud_murakumo.cosci uses: a small enumerable gene pool of
  line-type/delivery/register variations, persona-flavored via the durable
  loop's use of `:persona/tags` in voice.judge's system prompt.

  Domain divergence from the illust sibling actor's generate.cljc (worth
  stating explicitly, not silently): illust's `:prompt` is an
  image-diffusion prompt, where a comma-joined list of descriptors
  ('subject, style, lighting') is the idiomatic prompt shape. This actor's
  `:prompt` is literally THE TEXT TO BE SPOKEN by the TTS engine — a
  comma-joined descriptor list is not a sentence a narrator would ever say,
  so `round-candidates` here composes the picked line-type/register into one
  complete, closed-pool sentence (`line-bank`) and layers delivery on as a
  short trailing aside (`delivery-asides`), rather than concatenating raw
  gene values into the text. `:persona/tags` therefore is NOT interpolated
  into `:prompt` (unlike illust) — it stays available to voice.judge via the
  persona map, which is where 'does this line fit the persona' actually gets
  scored.

  round-candidates is a pure function of (persona, round, k) — re-running the
  same round number reproduces the same candidates; exploration across the
  pool happens by round number advancing (voice.loop) and by biasing one
  gene slot toward the previous round's elite (voice.cosci/evolve-round)."
  (:require [kotoba.lang.text :as str]))

(def gene-pool
  {:line-type ["a warm NPC greeting"
               "a quest hint dropped mid-conversation"
               "a shopkeeper's flavor remark about their wares"
               "a quiet ambient mutter to nobody in particular"
               "a brief warning about the weather ahead"
               "a cheerful farewell"]
   :delivery ["calm and unhurried" "slightly hurried, distracted"
              "warm and familiar" "formal and clipped"]
   :register ["young adult, casual" "older, weathered" "neutral, plain"]})

;; One complete, closed-pool sentence per [line-type register] pair — this is
;; the actual line a narrator would read aloud, not a descriptor to be
;; concatenated. Kept exhaustive/enumerable (6 line-types x 3 registers = 18
;; entries) so round-candidates stays a pure, reproducible closed hypothesis
;; pool, same discipline as illust's gene-pool.
(def line-bank
  {"a warm NPC greeting"
   {"young adult, casual" "Hey, didn't expect to see you out this early."
    "older, weathered" "Well now, look who's come wandering by."
    "neutral, plain" "Good to see you here."}
   "a quest hint dropped mid-conversation"
   {"young adult, casual" "If you're heading north, the old mill's been acting weird lately."
    "older, weathered" "You'd do well to check the ruins past the river, if you're headed that way."
    "neutral, plain" "There's something worth looking into near the old bridge."}
   "a shopkeeper's flavor remark about their wares"
   {"young adult, casual" "This one's fresh in, still smells like the workshop."
    "older, weathered" "I've sold finer, but not for a price like this."
    "neutral, plain" "Every piece on this shelf is worth a look."}
   "a quiet ambient mutter to nobody in particular"
   {"young adult, casual" "Ugh, still haven't fixed that fence."
    "older, weathered" "Every year it's the same dust on this step."
    "neutral, plain" "Nothing ever changes around here."}
   "a brief warning about the weather ahead"
   {"young adult, casual" "Storm's rolling in fast, you'll want to find cover."
    "older, weathered" "Sky's gone that color again, best get inside."
    "neutral, plain" "Weather's turning — take shelter soon."}
   "a cheerful farewell"
   {"young adult, casual" "See you around, take care out there!"
    "older, weathered" "Safe travels, and don't be a stranger."
    "neutral, plain" "Farewell, and good luck on the road."}})

;; A short trailing aside layered onto the base line for delivery — "formal
;; and clipped" deliberately adds nothing (its cue IS brevity, not an
;; addition). Never the raw gene value itself (see append-aside).
(def delivery-asides
  {"calm and unhurried" " — no rush."
   "slightly hurried, distracted" " — sorry, in a hurry."
   "warm and familiar" " — good to see you."
   "formal and clipped" ""})

(defn- append-aside
  "Splice a trailing aside onto one sentence, keeping the result a single
  sentence with exactly one closing punctuation mark."
  [sentence aside]
  (if (str/blank? aside)
    sentence
    (str (str/replace sentence #"[.!?]+\s*$" "") aside)))

(defn build-line
  "line-type + delivery + register -> the actual sentence to be spoken."
  [line-type delivery register]
  (append-aside (get-in line-bank [line-type register]) (get delivery-asides delivery)))

(defn- pick [xs seed n] (nth xs (mod (+ seed n) (count xs))))

(defn- gene-for
  "One candidate's gene map. `bias` (from voice.cosci/evolve-round's elite,
  or nil on round 0) pins ONE randomly-chosen slot to the prior winner's
  value instead of round-robining it — elitism without literal crossover
  machinery, honest about being a small closed pool rather than a genuine
  genetic search."
  [round i bias]
  (let [raw {:line-type (pick (:line-type gene-pool) round i)
             :delivery  (pick (:delivery gene-pool) round (+ i 1))
             :register  (pick (:register gene-pool) round (+ i 2))}]
    (if (and bias (pos? round) (zero? (mod (+ round i) 3)))
      (merge raw (select-keys bias [(nth [:line-type :delivery :register] (mod round 3))]))
      raw)))

(defn round-candidates
  "persona + round n (0-based) + k candidates + optional elite bias
  -> [{:candidate/id :prompt :gene :params} ...]. `:prompt` is the literal
  text to be spoken by the TTS engine."
  ([persona n k] (round-candidates persona n k nil))
  ([_persona n k bias]
   (vec
    (for [i (range k)]
      (let [{:keys [line-type delivery register]} (gene-for n i bias)]
        {:candidate/id (str "r" n "-c" i)
         :prompt (build-line line-type delivery register)
         :gene {:line-type line-type :delivery delivery :register register}
         :params {}})))))
