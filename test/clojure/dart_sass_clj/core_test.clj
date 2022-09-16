(ns dart-sass-clj.core-test
  (:require [clojure.java.io :as io]
            [dart-sass-clj.core :as sass]
            [clojure.test :refer [deftest testing is]]))

(defn compile-file
  [compiler path]
  (->> path
       (io/file)
       (sass/compile-result compiler)
       (.getCss)))

(deftest compiler-test
  (testing "Compile to string"
    (testing "compressed output style"
      (let [c (sass/compiler {:output-style :compressed})]
        (is (= (compile-file c "test/resources/dependent.scss")
               ".class-dependency{display:block}.class-dependent{display:none}"))

        (is (= (sass/compile-str c (slurp "test/resources/dependent.scss"))
               ".class-dependency{display:block}.class-dependent{display:none}"))))

    (testing "expanded output style"
      (let [c (sass/compiler {:output-style :expanded})]
        (is (= (compile-file c "test/resources/dependent.scss")
               ".class-dependency {\n  display: block;\n}\n\n.class-dependent {\n  display: none;\n}"))))

    (testing "compile jar bundled sass with transitive webjar import"
      (let [c (sass/compiler {:output-style :expanded})]
        ;; test/resources/bundle.scss imports webjar.scss from
        ;; test/resources/bundle.jar, which in turn imports the entire
        ;; org.webjars.bower/bootstrap 5.1.3 dependency tree from
        ;; org.webjars.bower/bootstrap.
        (is (= (hash (compile-file c "test/resources/bundle.scss"))
               1592862794))))))
