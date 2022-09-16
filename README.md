<img src="https://i.imgur.com/GH71uSi.png" title="zalky" align="right" width="250"/>

# Dart-sass-clj

[![Clojars Project](https://img.shields.io/clojars/v/io.zalky/dart-sass-clj?labelColor=blue&color=green&style=flat-square&logo=clojure&logoColor=fff)](https://clojars.org/io.zalky/dart-sass-clj)

An embedded dart sass compiler and watch task for Clojure.

Implemented as a wrapper around the
[dart-sass-java](https://github.com/larsgrefer/dart-sass-java)
embedded host, and the [Axle
watcher](https://github.com/zalky/axle). Aims to be a drop-in
replacement for the deprecated
[sass4clj](https://github.com/Deraen/sass4clj).

While it's true that you can [shell out to
`dart-sass`](https://gist.github.com/Deraen/695c94848d3ee05990239d403f7fe733)
that process will not have access to resources on the java
classpath. If you depend on webjars, like `org.webjars/bootstrap`, or
your own jar bundles, then you are out of luck. With this embedded
compiler you have access to anything on the classpath, as well as
anything on disk.

## Getting Started

First, you will probably need some version of [Dart
Sass](https://sass-lang.com/dart-sass) installed.

With that prerequisite out of the way, add the following dependency in
your `deps.edn`:

```clj
io.zalky/dart-sass-clj {:mvn/version "0.2.0"}
```

You should then be able to configure an alias that looks something
like:

```clj
{:sass {:extra-deps {io.zalky/dart-sass-clj {:mvn/version "0.2.0"}}
        :main-opts  ["-m" "dart-sass-clj.core"
                     "--source-dirs" "[\"src/scss/path\" \"other/scss\"]"
                     "--target-dir" "resources/assets/"
                     "--output-style" ":expanded"
                     "--source-maps"
                     "--watch"]}}
```

Here we have configured the following:

1. `--source-dirs` is a list of paths that `dart-sass-clj` will search
   for sass main files. If `--source-dirs` is omitted, then each
   directory on the classpath is searched.

2. `--target-dir` is where all emitted files (css, source-maps etc...)
   will be written.

3. `--output-style` specifies whether CSS will be compiled in
   `:expanded` or `:compressed` mode. Default is `:compressed`.

4. `--source-maps` specifies that source maps and their corresponding
   scss source will be emitted along with compiled css. Default is
   false.

5. `--watch` will start a watch task and recompile all main files if
   any sass files in `--source-dirs` change. Unfortunately, because
   there is currently no dependency tracking, there is no way to know
   specifically which main file should be recompiled, so they all are.

You can display the full list of options with:

```
clojure -M:sass -m dart-sass-clj.core --help
```

## Input and ouput directory structure

Any file ending with `.scss` or `.sass` is considered sass source.

The compiler recursively searches each source path for sass main
files, which in the semantics of dart-sass is any sass file that does
not start with an underscore. Each main file is then treated as a root
node, which together with its import tree is compiled into a single
css file. These css files are then emitted to the target directory,
preserving any sub directory structure relative to the original source
path.

Given the following source tree, where `main.scss` imports
`_dependency.scss`:

```
source-dir/subdir/main.scss
source-dir/subdir/sub/_dependency.scss
```

Then the compiler would emit:

```
target-dir/subdir/main.css
```

If you have source maps turned on, the compiler will additionally emit
a source map file, as well as its associated source tree:

```
target-dir/subdir/main.css
target-dir/subdir/main.css.map
target-dir/subdir/scss/subdir/main.scss
target-dir/subdir/scss/subdir/sub/_dependency.scss
```

## Imports

`dart-sass-clj` attempts to preserve the import semantics of both
dart-sass and
[`sass4clj`](https://github.com/Deraen/sass4clj#import-load-order).

First note that import statements can choose to omit the file
extension, or the leading underscore. So `@import "module";` will
match `@import "_module";` will match `@import
"_module.scss";`, but not necessarily the reverse.

Then for a file at `{path}/{name}` containing `@import "{module}";`,
the following will be searched to resolve the import:

1. Local file at path `{path}/{module}.scss`:
2. Local file in any source-dir `{source-dir}/{module}.scss`
3. Classpath resource `(io/resource "{module}.scss")`

As a specific example, given `source-dir/subdir/main.scss` containing `@import
"some/other/dependency";`:

1. `source-dir/subdir/some/other/_dependency.scss`
2. `source-dir/some/other/_dependency.scss`
3. `classpath-folder/some/other/_dependency.scss`

Where a file with no underscore, or a `.sass` extension would also be
matched.

Finally, if it does not resolve to any of these, the compiler will
check webjars:

4. `@import "{package}/{module}";` will resolve to:
   - `META-INF/resources/webjars/{package}/{version}/{module}`

   Ex: `@import "bootstrap/scss/bootstrap";` will import:
   - `META-INF/resources/webjars/bootstrap/5.2.2/scss/bootstrap.scss`

## With Runway

If you are using [Runway](https://github.com/zalky/runway), you can
configure your alias via `:exec-args` instead:

```clj
{:sass {:extra-deps {io.zalky/runway        {:mvn/version "0.2.0"}
                     io.zalky/dart-sass-clj {:mvn/version "0.2.0"}}
        :exec-fn    runway.core/exec
        :exec-args  {dart-sass-clj.core/compile {:source-dirs  ["src/scss/"]
                                                 :target-dir   "resources/assets/"
                                                 :output-style :expanded
                                                 :watch        true
                                                 :source-maps  true}}}}
```

See the Runway
[README.md](https://github.com/zalky/runway/blob/master/README.md) for
more details on usage.

## License

Dart-sass-clj is distributed under the terms of the Apache License 2.0.

