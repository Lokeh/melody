(ns melody.core
  (:require [lilactown.harmony :as harmony]
            [lilactown.dynamic-scope :as ds]))


(comment
  (extend-protocol IPrintWithWriter
    js/Set
    (-pr-writer [this writer opts]
      (pr-sequential-writer
       writer pr-writer "#object[Set [" " " "]]"
       opts this))))


(defprotocol ISource
  (-send [src x]))


(defprotocol IReactive
  (-set-order [node n])
  (-calculate [node]))


(defprotocol INode
  (-order [node])
  (-add-edge [a b])
  (-remove-edge [a b]))


(defprotocol ISink
  (-dispose [sink]))


(def ^:dynamic *reactive-context*)


(def ^:dynamic *transaction*)


;;
;; The general approach we use for optimally calculating nodes is a topological
;; sort, where each node's height is: max(height of nodes I depend on) + 1
;;
;; This is implemented across the code piece; first here, where we sort by the
;; `-order` method return value, and `-set-order`, which ensures that `-order`
;; is always monotonically increasing for a given node, and `-calculate` which
;; handles calling `-set-order` after each calculation.
;;


(defn- calculate-all-nodes!
  [initial-nodes]
  (loop [nodes (js/Set. (to-array initial-nodes)) ;; copy set
         ;; TODO remove governor
         n 100]
    ;; TODO don't sort every iteration
    (when-some [node (first (sort-by -order nodes))]
      (when (> n 0)
        (when (< n 80)
          (prn :runaway))
        (doseq [node' (-calculate node)]
          (.add nodes node'))
        (.delete nodes node)
        (recur nodes
               (dec n))))))


(deftype Source [ref reducer edges meta]
  IMeta
  (-meta [_]
    meta)

  IDeref
  (-deref [this]
    (when (some? *reactive-context*)
      ;; `js/Set`
      (.add ^js *reactive-context* this))
    (harmony/deref ref))

  ISource
  (-send [_ x]
    (doto (harmony/branch)
      ;; calculate new state of source
      (.add #(harmony/alter
              ref
              (fn [current]
                (reducer current x))))
      ;; calculate nodes
      (.add #(calculate-all-nodes! edges))
      (.commit))
    nil)

  INode
  (-order [_] 0)
  (-add-edge [_ node]
    (.add ^js edges node))
  (-remove-edge [_ node]
    (.delete ^js edges node)))


(defn- set-difference
  [s1 s2]
  (->> s1
       (filter #(not (.has s2 %)))
       (js/Set.)))


(defn- set-intersection
  [s1 s2]
  (-> s1
      (filter #(.has s2 %))
      (js/Set.)))


(deftype Node [state f xf
               ^:mutable initialized? ^:mutable
               to-edges ^:mutable from-edges ^:mutable
               order meta]
  IMeta
  (-meta [_]
    meta)

  IDeref
  (-deref [this]
    (if (some? *reactive-context*)
      (do (.add ^js *reactive-context* this)
          (when-not initialized?
            ;; lazily calculate
            (-calculate this)))
      (when-not initialized?
        ;; lazily calculate
        (-> (harmony/branch)
            (.add #(-calculate this))
            (.commit))))
    (harmony/deref state))

  INode
  (-order [_] order)
  (-add-edge [_ node]
    (.add ^js to-edges node))
  (-remove-edge [this node]
    (.delete ^js to-edges node)
    (when (zero? (.-size ^js to-edges))
      (set! initialized? false)
      ;; disposing. remove it from the graph
      (doseq [node from-edges]
        (-remove-edge node this))))

  IReactive
  (-set-order [_ n]
    (when (> n order)
      (set! order n)))
  (-calculate [this]
    (let [from-edges' (js/Set.)]
      ;; run `f` with `*reactive-context*` set so that we can diff our from-edges
      ;; and clean up any that have become stale
      (binding [*reactive-context* from-edges']
        ;; TODO run `xf` for transducing
        (harmony/set state (f)))

      (when (some? *reactive-context*)
        ;;
        ;; TODO this all needs to _after_ the transaction has committed.
        ;;

        ;; remove ourself from from-edges that are stale
        (doseq [node (set-difference from-edges from-edges')]
          (-remove-edge node this))

        ;; TODO only do this for difference the other way maybe?
        (doseq [node from-edges']
          (-add-edge node this)
          ;; set the order of this node to be at least as big as it's biggest edge
          ;; to enable topological sorting when calculating
          (-set-order this (inc (-order node))))

        ;; set current from-edges
        (set! from-edges from-edges')

        (set! initialized? true))

      ;; return edges to be calculated
      to-edges)))


(deftype Sink [state f ^:mutable from-edges ^:mutable watch ^:mutable order meta]
  IDeref
  (-deref [_]
    (when *reactive-context*
      (throw (ex-info "Cannot deref sink inside of a node" {})))
    (harmony/deref state))

  ISink
  (-dispose [this]
    ;; try to allow this sink and any of it's dependencies to be GCd
    (doseq [node from-edges]
      (-remove-edge node this))
    (set! from-edges nil)
    (set! watch nil))

  IReactive
  (-set-order [_ n]
    (when (> n order)
      (set! order n)))
  (-calculate [this]
    (let [from-edges' (js/Set.)
          old (harmony/deref state)]
      (binding [*reactive-context* from-edges']
        (harmony/set state (f)))

      ;; remove ourself from edges that are stale
      (doseq [node (set-difference from-edges from-edges')]
        (-remove-edge node this))

      ;; TODO only do this for difference
      (doseq [node from-edges']
        (-add-edge node this)
        ;; set the order of this node to be at least as big as it's biggest edge
        ;; to enable topological sorting when calculating
        (-set-order this (inc (-order node))))

      ;; set current from-edges
      (set! from-edges from-edges')

      ;; TODO run watch after commit
      (watch this old (harmony/deref state))

      ;; sinks never have to-edges
      nil)))


(defn source
  [reducer default]
  (->Source (harmony/ref default) reducer (js/Set.) nil))


(defn node
  [f]
  (let [node (->Node
              (harmony/ref nil)
              f
              nil ;; `xf`
              false ;; `initialized?`
              (js/Set.) ;; `from-edges`
              (js/Set.) ;; `to-edges`
              ;; assume at least order 1
              1
              ;; meta
              nil)]
    node))


(defn sink
  [input-fn on-change]
  (let [s (->Sink
           (harmony/ref nil)
           input-fn
           (js/Set.) ;; from-edges
           on-change
           1 ;; default order
           ;; meta
           nil)]
    ;; run sink eagerly
    (-> (harmony/branch)
        (.add #(-calculate s))
        (.commit))
    s))


(defn send [src message]
  (-send src message))


(comment
  (def a (source #(case %2
                    :inc (inc %1)
                    :dec (dec %1)) 0))

  (def b (node #(inc @a)))

  (def c (node #(dec @a)))

  (def d (node #(do (prn 'd)
                    (+ @b @c))))

  (def s (sink #(deref d) (fn [_ o n] (prn o n))))

  (-order a)

  (-order b)

  (-order c)

  (-order d)

  (send a :dec)

  @a

  @b

  @c

  @d

  (-dispose s)
)
