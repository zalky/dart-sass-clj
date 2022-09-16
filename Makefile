.PHONY: sass

version-number  = 0.2.0
group-id        = io.zalky
artifact-id     = dart-sass-clj
description     = An embedded dart-sass compiler and watch task for Clojure
license         = :apache
url             = https://github.com/zalky/dart-sass-clj

include make-clj/Makefile

sass:
	clojure -X:test/sass-watcher dart-sass-clj.core/exec '{dart-sass-clj.sass/compile {:target-dir "resources/" :watch false}}'
