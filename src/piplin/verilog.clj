(ns piplin.verilog
  (:refer-clojure :exclude [replace])
  (:use [slingshot.slingshot])
  (:use [clojure.walk :only [postwalk]]) 
  (:use [clojure.set :only [map-invert]])
  (:use [clojure.string :only [join replace]]) 
  (:use [piplin modules types])
  (:use [piplin [math :only [bit-width-of piplin-clojure-dispatch bits]]]))

(defn sanitize-str
  "Takes a string and makes it safe for verilog."
  [s]
  (replace s \- \_))

(defn make-union-verilog
  [tag padding value]
  (str "{" tag
       (when (pos? padding)
         (str ", " padding "'b" 0)) 
       ", " value "}"))

(defmulti verilog-repr
  kindof
  :hierarchy types)
(defmethod verilog-repr :default
  [x]
  (throw+ (error "cannot convert to verilog:" x)))

(defmethod verilog-repr :uintm
  [x]
  (let [t (typeof x)
        i (value x)
        w (bit-width-of t)] 
    (str w "'d" i)))

(defmethod verilog-repr :boolean
  [x]
  (if x "1'b1" "1'b0"))

(defmethod verilog-repr :bits
  [x]
  (let [t (typeof x)
        b (value x)
        w (bit-width-of t)]
    (if (zero? w)
      "0'b0"
      (str w "'b" (join b)))))

(defmethod verilog-repr :enum
  [x]
  (let [t (typeof x)
        keymap (:keymap t)
        e (value x)
        w (bit-width-of t)]
    (str (verilog-repr (keymap e)))))

(defmethod verilog-repr :union
  [x]
  (let [t (typeof x)
        {:keys [schema enum]} t
        v (first (value x)) 
        tag (key v)
        v (val v)
        w (bit-width-of t)
        padding (- w
                   (bit-width-of enum)
                   (bit-width-of (tag schema)))]
    (make-union-verilog
      (verilog-repr (enum tag))
      padding
      (verilog-repr v)
      )))

(defmethod verilog-repr :bundle
  [x]
  (verilog-repr (piplin.math/serialize x)))

(defn lookup-expr
  [table expr]
  (if (pipinst? expr)
    (verilog-repr expr)
    (if-let [name (get table expr)]
      name
      (do
        "UNKNOWN_QUANTITY"
       #_(throw+ (error expr "not found in" table))))))

(defmulti verilog-of
  (fn [ast name-lookup] (if (pipinst? ast)
                          ::immediate
                          (:op (value ast)))))

(defmethod verilog-of :default
  [ast name-lookup]
  (throw+ (error "cannot convert to verilog:" ast)))

(defmethod verilog-of ::immediate
  [ast name-lookup]
  (verilog-repr ast))

(defmethod verilog-of :port
  [ast name-lookup]
  (lookup-expr name-lookup ast))

(defn merged-args
  [ast]
  (apply merge (map (value ast) [:args :consts])))

(defmacro let-args
  [ast name-lookup argvec & body]
  (let [lookups (mapcat #(vector %
                              `(lookup-expr ~name-lookup ~%))
                     argvec)]
    `(let [{:keys ~argvec} (merged-args ~ast)
           ~@lookups]
       ~@body)))

(defmethod verilog-of :make-union
  [ast name-lookup]
  (let [t (typeof ast)
        {:keys [schema enum]} t
        {:keys [tag val]} (merged-args ast)
        w (bit-width-of t)
        padding (- w
                   (bit-width-of enum)
                   (bit-width-of (tag schema)))]
    (make-union-verilog
      (lookup-expr name-lookup (enum tag))
      padding
      (lookup-expr name-lookup val))))

(defmethod verilog-of :get-value
  [ast name-lookup]
  (let [valtype (typeof ast) 
        union (:u (merged-args ast))
        top  (dec (bit-width-of valtype))]
    (str (lookup-expr name-lookup union)
         "[" top (when-not (zero? top) ":0") "]"))) 

(defmethod verilog-of :get-tag
  [ast name-lookup]
  (let [enum (typeof ast) 
        union (:u (merged-args ast))
        top (dec (bit-width-of (typeof union)))
        bottom (inc (- top (bit-width-of enum)))]
    (str (lookup-expr name-lookup union)
         "[" top 
         (when-not (= top bottom)
           (str ":" bottom))
         "]"))) 

(defmethod verilog-of :make-bundle
  [ast name-lookup]
  (let [schema-ks (keys (:schema (typeof ast)))
        bundle-inst (merged-args ast)
        ordered-vals (map #(lookup-expr name-lookup
                                        (get bundle-inst %))
                          schema-ks)]
    (str "{" (join ", " ordered-vals) "}")))

(defn compute-key-offsets
  "Returns a map from keys to pairs. The pairs
  are the low (inclusive) to high (inclusive)
  bit indices in the verilog representation."
  [bundle-type]
  (let [schema (:schema bundle-type)
        key-widths (map (comp bit-width-of
                              (partial get schema))
                        (keys schema))
        offsets (reductions + (conj key-widths 0))
        total (reduce + key-widths)
        pairs (partition 2 1 (map (partial - total) offsets))
        pairs (map (fn [[x y]] [(dec x) y]) pairs)
        ]
    (into {} (map vector (keys schema) pairs))))

(defmethod verilog-of :bundle-assoc
  [ast name-lookup]
  (let [{:keys [bund k v]} (merged-args ast)
        t (typeof ast)
        offsets (compute-key-offsets t)
        w (bit-width-of t)
        [high low] (get offsets k)
        v (lookup-expr name-lookup v)
        bund (lookup-expr name-lookup bund)] 
    (str "{"
         (when-not (= (dec w) high)
           (str bund "[" (dec w) ":" (inc high) "], "))
         v
         (when-not (= low 0)
           (str ", " bund "[" (dec low) ":0]"))
         "}")))

(defmethod verilog-of :bundle-key
  [ast name-lookup]
  (let [{:keys [bund key]} (merged-args ast)
        offsets (compute-key-offsets (typeof bund))
        [high low] (get offsets key)]
    (str (lookup-expr name-lookup bund) "[" high ":" low "]")))

(defmethod verilog-of :slice
  [ast name-lookup]
  (let [{:keys [expr high low]} (merged-args ast)]
    (str (lookup-expr name-lookup expr) "[" (dec high) ":" low "]")))

(defmethod verilog-of :bit-cat
  [ast name-lookup]
  (let-args ast name-lookup [b1 b2]
    (str "{" b1 ", " b2 "}")))

(defmethod verilog-of :mux2
  [ast name-lookup]
  (let [t (typeof ast)
        {:keys [sel v1 v2]} (merged-args ast)]
    (str (lookup-expr name-lookup sel) " ? "
         (lookup-expr name-lookup v1) " : "
         (lookup-expr name-lookup v2))))

(defmethod verilog-of :+
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " + " rhs)))

(defmethod verilog-of :-
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " - " rhs)))

(defmethod verilog-of :*
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " * " rhs)))

(defmethod verilog-of :>
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " > " rhs)))

(defmethod verilog-of :>=
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " >= " rhs)))

(defmethod verilog-of :<
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " - " rhs)))

(defmethod verilog-of :<=
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " <= " rhs)))

(defmethod verilog-of :bit-and
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " & " rhs)))

(defmethod verilog-of :bit-or
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " | " rhs)))

(defmethod verilog-of :bit-xor
  [ast name-lookup]
  (let-args ast name-lookup [lhs rhs]
            (str lhs " ^ " rhs)))

(defmethod verilog-of :=
  [ast name-lookup]
   (let [{:keys [x y]} (merged-args ast)]
     (str (lookup-expr name-lookup x) " == " (lookup-expr name-lookup y))))

(defn verilog-noop-passthrough
  [ast name-lookup]
  (let [args (merged-args ast)]
    (when-not (contains? args :expr)
      (throw+ (error "must have an :expr" args)))
    (when (not= 1 (count args))
      (throw+ (error "must have exactly one expr")))
    (verilog-of (:expr args) name-lookup)))

(defmethod verilog-of :cast
  [ast name-lookup]
  (verilog-noop-passthrough ast name-lookup))
(defmethod verilog-of :noop
  [ast name-lookup]
  (verilog-noop-passthrough ast name-lookup))
(defmethod verilog-of :serialize
  [ast name-lookup]
  (verilog-noop-passthrough ast name-lookup))

(defn array-width-decl [x]
  (if (zero? x)
    ""
    (str "[" (dec x) ":0] ")))

(defn render-single-expr
  "Takes an expr and a name table and
  returns an updated name table and a string
  to add to the text of the verilog"
  [expr name-table]
  (cond
    (pipinst? expr)
    [(assoc name-table expr (verilog-repr expr)) ""]
    (= :use-core-impl (piplin-clojure-dispatch expr))
    (do
      (println "WARNING: trying to render clojure type, skipping")
      [name-table ""])
    ;The is common subexpression elimination
    (contains? name-table expr)
    [name-table ""]
    :else
    (let [name (name (gensym))
          indent "  "
          bit-width (bit-width-of (typeof expr))
          wire-decl (str "wire " (array-width-decl bit-width))
          assign " = "
          body (verilog-of expr name-table)
          terminator ";\n"]
      [(assoc name-table expr name)
       (str indent
            wire-decl
            name
            assign
            body
            terminator)])))

;todo this can take a long time
(defn verilog
  "first, recurses to non-const members of the expr.
  then, renders all const members of the expr.
  finally, renders the expr itself.

  returns the updated name-table and text"
  ([expr name-table]
   (verilog expr name-table "")) 
  ([expr name-table text]
   (letfn [(render-expr [expr name-table text]
             (let [[name-table partial-text]
                   (render-single-expr expr name-table)]
               [name-table (str text partial-text)]))]
     (if (pipinst? expr)
       (render-expr expr name-table text)
       (let [args (vals (:args (value expr)))
             [name-table text]
             (if (seq args)
               (reduce
                 (fn [[name-table text] expr]
                   (verilog expr
                            name-table
                            text))
                 [name-table text]
                 args)
               [name-table text])
             consts (vals (:consts (value expr)))
             [name-table text]
             (reduce
               (fn [[name-table text] expr]
                 (verilog expr
                          name-table
                          text))
               [name-table text]
               consts)]
         (render-expr expr name-table text))))))

(defn module-decl
  "Declares a module, using a map from strings
  to strings to populate the connections."
  [decl-name module connections]
  (str "  " (sanitize-str (name (:token module))) " " decl-name "(\n"
       "    .clock(clock), .reset(reset)" (when (seq connections) \,)
       "\n"
       (join ",\n"
         (map (partial str "    ")
                  (map (fn [[port conn]]
                         (str \. port \( conn \)))
                       connections)))
       "\n"
       "  );\n"))

(defn init-name-table [module-inst]
  (let [module-ports (->> module-inst
                       :ports
                       (reduce (fn [accum port]
                                 (let [{port-kw :port} (value port)]
                                   (assoc accum port (name port-kw))))
                               {}))
        ;We must find all the submodule port
        ;references to add to the name-table
        module-exprs (walk-connects module-inst
                                    #(get-in % [:args :expr])
                                    concat)
        subports (mapcat (fn [expr]
                        (walk-expr expr
                          (fn [expr]
                              (if (= (:port-type (value expr))
                                    :subport)
                               [expr] nil))
                          concat)) module-exprs)
        subports-map (->> (set subports)
                       (mapcat (fn [{:keys [module port] :as subport}]
                                 [subport (str (name module)
                                               \.
                                               (name port))]))
                       (apply hash-map))]
    (merge subports-map module-ports)))

(defn module->verilog
  [module]
  (when-not (= (:type module) :module)
    (throw+ (error module "must be a module")))
  (let [ports  (mapcat (comp keys #(get module %)) [:inputs :outputs])
        inputs (->> (:inputs module)
                 (mapcat (fn [[k v]] [(name k) (bit-width-of v)]))
                 (apply hash-map))
        outputs (->> (:outputs module)
                  (mapcat (fn [[k v]] [(name k) (bit-width-of (typeof v))]))
                  (apply hash-map))
        feedbacks (->> (:feedback module)
                  (mapcat (fn [[k v]] [(name k) (bit-width-of (typeof v))]))
                  (apply hash-map))
        initials (->> (merge (:outputs module) (:feedback module))
                   (mapcat (fn [[k v]] [(name k) (verilog-repr v)]))
                   (apply hash-map))
        submodules (->> (:modules module)
                     (map (fn make-module-decls [[k v]]
                            (module-decl (name k) v {}))))
        name-table (init-name-table module)
        connections (->> module
                      :body
                      (filter #(= :register (:type %)))
                      (map (comp :args value))
                      (map (fn [{:keys [reg expr]}]
                             [(-> reg value :port name)
                              expr])))
        input-connections (->> module
                      :body
                      (filter #(= (:type %) :subport))
                      (map (comp :args value))
                      (map (fn [{:keys [reg expr]}]
                               [(->> reg value ((juxt :module :port)) (map name) (join \.))
                                expr])))
        [name-table body] (reduce
                            (fn [[name-table text] expr]
                              (verilog expr name-table text))
                            [name-table ""] (map second (concat input-connections connections)))
        ]
    (str "module " (sanitize-str (name (:token module))) "(\n"
         "  clock,\n"
         "  reset,\n"
         (join ",\n" (map #(str "  " (name %)) ports)) "\n"
         ");\n"
         "  input wire clock;\n"
         "  input wire reset;\n"
         (join "\n" submodules)
         (when (seq inputs)
           (str "\n"
                "  //inputs\n"
                (join (map (fn [[input width]]
                             (str "  input wire " (array-width-decl width) input ";\n"))
                           inputs))))
         (when (seq outputs)
           (str "\n"
                "  //outputs\n"
                (join (map (fn [[output width]]
                             (str "  output reg " (array-width-decl width) output ";\n"))
                           outputs))))
         (when (seq feedbacks)
           (str "\n"
                "  //feedback\n"
                (join (map (fn [[feedback width]]
                             (str "  reg " (array-width-decl width) feedback ";\n"))
                           feedbacks))))
         "\n"
         "\n"
         body
         "\n"
         "\n"
         "  always @(posedge clock)\n"
         "    if (reset) begin\n"
         (join (map (fn [[name val]]
                      (str "      " name " <= " val ";\n"))
                    initials))
         "    end else begin\n"
         (join (map (fn [[name expr]]
                      (str "      " name " <= " (lookup-expr name-table expr) ";\n"))
                    connections))
         "    end\n"
         "\n"
         (join (map (fn [[name expr]]
                      (str "  assign " name " = " (lookup-expr name-table expr) ";\n"))
                    input-connections))
         "endmodule\n")))

(defn regs-for-inputs
  [module]
  [(map #(let [w (-> % val bit-width-of)]
           (str "reg "
                (array-width-decl w)
                (-> % key name)
                " = " w "'b" (join (repeat w \z)) ";\n"))
        (:inputs module))
   (map #(let [n (-> % key name)] (str \. n \( n \)))
        (:inputs module))])

(defn wire-for-regs
  [module]
  [[]
   (map #(str \. (-> % key name) "()")
        (mapcat #(get module %) [:outputs]))]) ;TODO: should this include :feedback?

(defn assert-hierarchical
  [indent dut-name var val]
  (let [dut-var (str dut-name \. var) 
        assertion-str (str dut-var " !== " val)]
    (str indent "if (" assertion-str ") begin\n"
         indent "  $display(\"failed assertion: " assertion-str ", instead is %b\", " dut-var ");\n"
         indent "  $finish;\n"
         indent "end\n")))

(defn assert-hierarchical-cycle
  "indent is prepended as indentation (usually whitespace).
  
  dut-name is the name of the dut module; registers will
  be asserted hierarchically rooted at the dut.
  
  cycle-map is a map from vectors of keywords representing
  the hierarchical path to a register (excluding the dut's name)
  to the values."
  [indent dut-name cycle-map]
  (reduce (fn [text [path val]]
            (when-not (vector? path)
              (throw+ "Path must be a vector"))
            (str text
                 (assert-hierarchical indent dut-name
                                      (join \. (map name path))
                                      (verilog-repr val))))
          ""
          cycle-map))

(defn make-testbench
  "Produces verilog that will run a test
  against a given module. samples is a seqable
  of maps, where the keys are all the inputs
  and registers of the module, and the simulation
  is run with those inputs and asserting those
  states. The generated verilog will do this check,
  and generate the appropriate clock and reset signanls."
  [module samples]
  (let [[input-decls input-connects] (regs-for-inputs module)
        [output-decls output-connects] (wire-for-regs module)
        dut-name "dut"]
    (str
      "module test;\n"
      "  reg clk = 0;\n"
      "  always #5 clk = !clk;\n"
      "  reg rst = 1;\n"
      "\n"
      (join (map (partial str "  ") input-decls))
      (join (map (partial str "  ") output-decls))
      "  " (sanitize-str (name (:token module))) " " dut-name "(\n"
      "    .clock(clk), .reset(rst)" (when
                                       (->
                                         (concat
                                           input-connects
                                           output-connects)
                                         seq) \,)
      "\n"
      (join (map (partial str "    ")
                 (concat input-connects output-connects))) "\n"
      "  );\n"
      "\n"
      "  initial begin\n"
      ;"    $dumpfile(\"dump.vcd\");\n"
      ;"    $dumpvars(0);\n"
      "    #10 rst = 0;\n"
      (->> samples
        
        (map #(assert-hierarchical-cycle "    "
                                         dut-name
                                         %))
        (map #(str % "    #10\n"))
        join)
      "    $display(\"test passed\");\n"
      "    $finish;\n"
      "  end\n"
      "endmodule")))

(defn modules->verilog
  "This takes a module (the root module),
  and returns a list of pairs where the first
  element is a module, the second element is
  that module's verilog."
  [root]
  (walk-modules root
    (fn visit [module]
      [[module (module->verilog module)]])
    (fn combine [x y]
      (let [left-modules (set (map first x))]
        (if (left-modules (ffirst y))
          x
          (concat x y))))))

(defn modules->all-in-one
  "Takes a root module and returns a string
  which is a single verilog file with the
  entire module hierarchy included."
  [root]
  (->> (modules->verilog root)
    (map second)
    (join "\n")))

;ex: (verilog (+ ((uintm 3) 0) (uninst ((uintm 3) 1))) {})

;way more awesome that this works:
(comment
  (def e (enum #{:a :b :c} ))
  (def b (bundle {:car e :cdr (uintm 4)})) 
  (def u (union {:x (uintm 5) :y b})) 

  (-> (verilog (union-match (uninst (u {:x ((uintm 5) 0)}))
  (:x x (bit-slice (serialize x) 1 4))
  (:y {:keys [car cdr]} #b00_1)) {}) second print))

;this is super cool:
(comment
  (defmodule counter [n]
    [:outputs [x ((uintm n) 0)]]
    (connect x (inc x)))
  (module->verilog (counter 8)))
(comment
(println (make-testbench (counter 8)
  (map (fn [x] {[:x] ((uintm 8) x)})
       (take 10 (iterate inc 0))))))
