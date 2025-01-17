(ns scopula.core
  "Handle scope logic

  scopes are case sensitive strings without any space that represent and
  authorization access. From OAuth2 RFC (https://tools.ietf.org/html/rfc6749#section-3.3):

  > The value of the scope parameter is expressed as a list of space-
  > delimited, case-sensitive strings.  The strings are defined by the
  > authorization server.  If the value contains multiple space-delimited
  > strings, their order does not matter, and each string adds an
  > additional access range to the requested scope.
  >
  >   scope       = scope-token *( SP scope-token )
  >   scope-token = 1*( %x21 / %x23-5B / %x5D-7E )


  In order to manage a fine grained authorizations this lib use a convention
  for scope formats.
  For example, we often need to distinguish between a full scope that will provide
  full access to some resource, and read-only access.
  Sometime we also want to limit the access to some sub-resource.
  Here are some example for our convention:

  `users`                      full access to users resource
  `users/profile`              access to users profile only
  `users/profile:read`         access to users profile read-only
  `users/profile/email:write`  access to users profile only email write-only


  Mainly `:` is only authorized to split between access read/write/rw
  (nothing implies rw)

  Sub resources are separated by `/` we can

  This library provide helper functions to check that

  users scope will also grants `users/profile/email` and `users/profile:read`

  We also provide helpers to normalize set of scopes:

  >>> (normalize-scopes #{\"users\" \"users/profile/email:read\" \"admin\"})
  #{\"users\" \"admin\"}

  as `users/profile/email:read` is redundant it was removed.

  Note scopes are meant to be used in an OAuth2 access in mind and thus
  are generally manipulated as a set of scopes.

  scopes that do not have any subpath are called _root scopes_.

  This is important because it is easy to add, union scopes.
  But it is generally impossible to remove just a sub-scope as it would
  mean we should know all the sub-paths of some root-scope and add the difference.

  Scope are additive by their nature.
  "
  (:require [clojure
             [set :as set]
             [string :as string]]))

(def scope-regex #"^[a-z0-9-]*(/[a-z0-9-]*)*(:(read|write|rw))?$")

(defn is-scope-format-valid?
  [scope]
  (re-matches scope-regex scope))

(defn to-scope-repr
  "Transform a textual scope as an internal representation to help
  check rules typically

  > \"foo\"
  {:path [\"foo\"]
   :access #{:read :write}}

  > \"foo/bar/baz:write\"
  {:path [\"foo\" \"bar\" \"baz\"]
   :access #{:write}}"
  [txt]
  (let [[path access] (string/split txt #":")]
    {:path (string/split path #"/")
     :access (case access
               "read"  #{:read}
               "write" #{:write}
               "rw"    #{:read :write}
               nil     #{:read :write}
               #{})}))

(defn scope-root
  "display the root of a scope

  >>> (scope-root \"foo/bar:read\")
  foo
  "
  [scope]
  (-> scope to-scope-repr :path first))

(defn is-sub-list?
  "check scope-path-list starts with req-list"
  [lst super-lst]
  (let [n (count lst)]
    (= (take n super-lst) lst)))

(defn repr-is-subscope?
  "return true if the scope is a subscope of the super scope"
  [scope-to-check super-scope]
  (and (set/superset? (:access super-scope) (:access scope-to-check))
       (is-sub-list? (:path super-scope) (:path scope-to-check))))

(defn is-subscope?
  "return true if the scope-to-check is a subscope of the super-scope"
  [scope-to-check super-scope]
  (repr-is-subscope? (to-scope-repr scope-to-check)
                     (to-scope-repr super-scope)))

(defn scope-repr-to-str
  [{:keys [path access]}]
  (str (string/join "/" path)
       (condp = access
         #{:read} ":read"
         #{:write} ":write"
         "")))

(defn- merge-accesses
  [[path reprs]]
  {:path path
   :access (apply set/union (map :access reprs))})

(defn repr-is-subsummed
  "return true if scope is contained by all the scopes

  Examples that return true:

  (is-subsummed \"foo\" #{\"foo\"})
  (is-subsummed \"foo:read\" #{\"foo\"})
  (is-subsummed \"foo/sub/scope\" #{\"foo\"})
  (is-subsummed \"foo/sub/scope:read\" #{\"foo\"})

  Examples that return false:

  (is-subsummed \"foo\" #{\"foo:read\" \"bar\"})
  (is-subsummed \"foo:read\" #{\"foo:read\" \"bar\"})
  (is-subsummed \"foo/sub/scope\" #{\"foo:read\"})"
  [repr-scope repr-scopes]
  (not (every? #(not (repr-is-subscope? repr-scope %)) repr-scopes)))

(defn repr-normalize-scopes
  [scopes-repr]
  (let [ssr (->> scopes-repr
                 (group-by :path)
                 (map merge-accesses)
                 set)]
    (->> ssr
         (filter #(not (repr-is-subsummed % (disj ssr %))))
         set)))

(defn normalize-scopes
  "Given a set of scope remove reduntant ones, and merge by access if possible"
  [scopes]
  (->> scopes
       (map to-scope-repr)
       repr-normalize-scopes
       (map scope-repr-to-str)
       set))

(defn accepted-by-scopes
  "scopes should be strings.
  if none of the string contains a `/` nor a `:`.
  It works as is a subset of.

  :scopes #{\"foo\" \"bar\"}
  only people with scopes which are super sets of
  #{\"foo\" \"bar\"}
  will be allowed to use the route.

  scopes are considered as path with read/write access.
  so \"foo/bar/baz:read\" is a sub-scope of \"foo\"
  and of \"foo:read\".

  So the more precise rule of access is.
  All mandatory scopes must be sub-scopes of at least one user scopes.

  Also mandatory the scopes and required should be normalized
  "
  [scopes required]
  (every? (fn [req-scope]
            (some #(repr-is-subscope? req-scope %) scopes))
          required))

(defn access-granted
  "check that the first parameter contains all the required scopes
  given as second parameter."
  [scopes required]
  (accepted-by-scopes (repr-normalize-scopes (map to-scope-repr scopes))
                      (repr-normalize-scopes (map to-scope-repr required))))

(def scopes-superset?
  "Returns true if the first set of scopes is a superset of the second set of scopes.
  This is another name for `access-granted` function"
  access-granted)

(defn scopes-subset?
  "flipped version of scopes-superset?.
  Returns true if the first set is a subset of the second set of scopes."
  [required scopes]
  (scopes-superset? scopes required))

(defn repr-scopes-difference
  "return the list of scopes that is not in "
  [scopes-1 scopes-2]
  (let [nsc-1 (repr-normalize-scopes scopes-1)
        nsc-2  (repr-normalize-scopes scopes-2)]
    (filter (fn [scope]
              (not (some #(repr-is-subscope? scope %) nsc-2)))
            nsc-1)))

(defn scopes-difference
  "return the set of scopes that is the first set of scopes without those
  of the second set of scopes"
  [scopes-1 scopes-2]
  (->> (repr-scopes-difference (map to-scope-repr scopes-1)
                               (map to-scope-repr scopes-2))
       (map scope-repr-to-str)
       set))

(defn root-scope
  "display the root of a scope

  >>> (root-scope \"foo/bar:read\")
  foo
  "
  [scope]
  (-> scope to-scope-repr :path first))

(defn is-root-scope?
  [scope]
  (= 1
     (count (-> scope to-scope-repr :path))))


(defn add-scope
  "Add the scope to a set of scopes"
  [scope scopes]
  (normalize-scopes (cons scope scopes)))

(defn scope-union
  "Unionize two set of scopes"
  [scopes-1 scopes-2]
  (normalize-scopes (set/union scopes-1 scopes-2)))

(defn raw-remove-root-scope
  "remove a root scope from a set of scopes.
  You should, most of the time, use remove-root-scope"
  [root-scope-to-remove scopes]
  (if (is-root-scope? root-scope-to-remove)
    (->> (for [scope scopes]
           (when-not (is-subscope? scope root-scope-to-remove)
             scope))
         (remove nil?)
         set)
    (throw (ex-info "We can't remove a sub scope, only root scope can be removed from a set of scopes, note access are supported"
                    {:scope root-scope-to-remove}))))

(def remove-root-scope
  (comp normalize-scopes raw-remove-root-scope))

(defn remove-root-scopes
  [root-scopes-to-remove scopes]
  (->> root-scopes-to-remove
       normalize-scopes
       (reduce (fn [acc-scopes scope]
                 (raw-remove-root-scope scope acc-scopes))
               scopes)
      normalize-scopes))
