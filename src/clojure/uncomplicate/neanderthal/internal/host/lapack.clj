;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.neanderthal.internal.host.lapack
  (:require [uncomplicate.commons.core :refer [with-release let-release]]
            [uncomplicate.neanderthal
             [block :refer [buffer offset stride]]
             [math :refer [pow sqrt abs]]]
            [uncomplicate.neanderthal.internal
             [api :refer [factory index-factory engine data-accessor info raw
                          create-vector create-ge create-gb create-sb create-sy fits-navigation?
                          nrm1 nrmi copy nrm2 amax trf tri trs con sv navigator region storage]]
             [common :refer [real-accessor dragan-says-ex ->LUFactorization ->CholeskyFactorization]]
             [navigation :refer [dostripe-layout full-storage]]]
            [uncomplicate.neanderthal.internal.host.cblas
             :refer [band-storage-reduce band-storage-map full-storage-map]])
  (:import uncomplicate.neanderthal.internal.host.CBLAS
           [uncomplicate.neanderthal.internal.api Region LayoutNavigator Matrix]
           uncomplicate.neanderthal.internal.navigation.BandStorage))

(defmacro with-lapack-check [expr]
  ` (let [err# ~expr]
      (if (zero? err#)
        err#
        (throw (ex-info "LAPACK error." {:error-code err# :bad-argument (- err#)})))))

;; =========================== Auxiliary LAPACK Routines =========================

;; ----------------- Common vector macros and functions -----------------------

(defmacro vctr-laset [method alpha x]
  `(do
     (with-lapack-check
       (~method CBLAS/ORDER_ROW_MAJOR (int \g) (.dim ~x) 1 ~alpha ~alpha (.buffer ~x) (.offset ~x) (.stride ~x)))
     ~x))

(defmacro vctr-lasrt [method x increasing]
  `(if (= 1 (.stride ~x))
     (do
       (with-lapack-check
         (~method (int (if ~increasing \I \D)) (.dim ~x) (.buffer ~x) (.offset ~x)))
       ~x)
     (dragan-says-ex "You cannot sort a vector with stride different than 1." {:stride (.stride ~x)})))

;; ----------------- Common GE matrix macros and functions -----------------------

(defmacro matrix-lasrt [method a increasing]
  `(let [incr# (int (if ~increasing \I \D))
         buff# (.buffer ~a)
         ofst# (.offset ~a)]
     (dostripe-layout ~a len# idx#
                      (with-lapack-check (~method incr# len# buff# (+ ofst# idx#))))
     ~a))

(defmacro ge-lan [method norm a]
  `(~method (.layout (navigator ~a)) ~norm (.mrows ~a) (.ncols ~a)
    (.buffer ~a) (.offset ~a) (.stride ~a)))

(defmacro ge-laset [method alpha beta a]
  `(do
     (with-lapack-check
       (~method (.layout (navigator ~a)) (int \g) (.mrows ~a) (.ncols ~a)
        ~alpha ~beta (.buffer ~a) (.offset ~a) (.stride ~a)))
     ~a))

;; ----------------- Common TR matrix macros and functions -----------------------

;; There seems to be a bug in MKL's LAPACK_?lantr. If the order is column major,
;; it returns 0.0 as a result. To fix this, I had to do the uplo# trick.
(defmacro tr-lan [method norm a]
  `(if (< 0 (.dim ~a))
     (let [reg# (region ~a)
           row-layout# (.isRowMajor (navigator ~a))
           lower# (.isLower reg#)
           uplo# (if row-layout# (if lower# \L \U) (if lower# \U \L))
           norm# (if row-layout# ~norm (cond (= ~norm (int \I)) (int \O)
                                             (= ~norm (int \O)) (int \I)
                                             :default ~norm))]
       (~method CBLAS/ORDER_ROW_MAJOR norm#
        (int uplo#) (int (if (.isDiagUnit reg#) \U \N))
        (.mrows ~a) (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a)))
     0.0))

(defmacro tr-lacpy [lacpy copy a b]
  `(let [nav# (navigator ~a)
         buff-a# (.buffer ~a)
         buff-b# (.buffer ~b)]
     (if (= nav# (navigator ~b))
       (with-lapack-check
         (let [reg# (region ~a)
               diag-pad# (long (if (.isDiagUnit reg#) 1 0))]
           (~lacpy (.layout nav#) (int (if (.isLower reg#) \L \U))
            (- (.mrows ~a) diag-pad#) (- (.ncols ~a) diag-pad#)
            buff-a# (+ (.offset ~a) (.index (storage ~a) diag-pad# 0)) (.stride ~a)
            buff-b# (+ (.offset ~b) (.index (storage ~b) diag-pad# 0)) (.stride ~b))))
       (full-storage-map ~a ~b len# offset-a# offset-b# ld-a# :skip
                         (~copy len# buff-a# offset-a# ld-a# buff-b# offset-b# 1)))
     ~b))

(defmacro tr-lascl [method alpha a]
  `(do
     (with-lapack-check
       (let [reg# (region ~a)
             diag-pad# (long (if (.isDiagUnit reg#) 1 0))]
         (~method (.layout (navigator ~a)) (int (if (.isLower reg#) \L \U))
          0 0 1.0 ~alpha (- (.mrows ~a) diag-pad#) (- (.ncols ~a) diag-pad#)
          (.buffer ~a) (+ (.offset ~a) diag-pad#) (.stride ~a))))
     ~a))

(defmacro tr-laset [method alpha beta a]
  `(do
     (with-lapack-check
       (let [reg# (region ~a)
             nav# (navigator ~a)
             diag-pad# (long (if (.isDiagUnit reg#) 1 0))
             idx# (if (.isLower reg#) (.index nav# (storage ~a) diag-pad# 0) (.index nav# (storage ~a) 0 diag-pad#))]
         (~method (.layout nav#) (int (if (.isLower reg#) \L \U))
          (- (.mrows ~a) diag-pad#) (- (.ncols ~a) diag-pad#)
          ~alpha ~beta (.buffer ~a) (+ (.offset ~a) idx#) (.stride ~a))))
     ~a))

;; ----------- Symmetric Matrix -----------------------------------------------------

(defmacro sy-lan [method norm a]
  `(if (< 0 (.dim ~a))
     (~method (.layout (navigator ~a)) ~norm (int (if (.isLower (region ~a)) \L \U))
      (.mrows ~a) (.buffer ~a) (.offset ~a) (.stride ~a))
     0.0))

(defmacro sy-lacpy [lacpy copy a b]
  `(let [nav# (navigator ~a)
         reg-a# (region ~a)
         buff-a# (.buffer ~a)
         buff-b# (.buffer ~b)]
     (if (or (= nav# (navigator ~b)) (not= (.uplo reg-a#) (.uplo (region ~b))))
       (with-lapack-check
         (~lacpy (.layout nav#) (int (if (.isLower (region ~a)) \L \U)) (.mrows ~a) (.ncols ~a)
          (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))
       (full-storage-map ~a ~b len# offset-a# offset-b# ld-a# :skip
                         (~copy len# buff-a# offset-a# ld-a# buff-b# offset-b# 1)))
     ~b))

(defmacro sy-lascl [method alpha a]
  `(do
     (with-lapack-check
       (~method (.layout (navigator ~a)) (int (if (.isLower (region ~a)) \L \U))
        0 0 1.0 ~alpha (.mrows ~a) (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a)))
     ~a))

(defmacro sy-laset [method alpha beta a]
  `(do
     (with-lapack-check
       (~method (.layout (navigator ~a)) (int (if (.isLower (region ~a)) \L \U))
        (.mrows ~a) (.ncols ~a) ~alpha ~beta (.buffer ~a) (.offset ~a) (.stride ~a)))
     ~a))

;; ----------- Banded Matrix --------------------------------------------------------

(defn band-storage-kl ^long [^BandStorage s]
  (.kl s))

(defn band-storage-ku ^long [^BandStorage s]
  (.ku s))

(defmacro gb-lan [langb nrm norm a]
  `(if (< 0 (.dim ~a))
     (let [stor# (full-storage ~a)
           fd# (.fd stor#)
           ld# (.ld stor#)
           kl# (band-storage-kl stor#)
           ku# (band-storage-ku stor#)
           buff# (.buffer ~a)]
       (cond
         (= (.mrows ~a) (.ncols ~a)) (with-release [work# (.createDataSource (data-accessor ~a) fd#)]
                                       (~langb ~norm fd# kl# ku# buff# (.offset ~a) ld# work#))
         (= ~norm (long \F)) (sqrt (band-storage-reduce ~a len# offset# acc# 0.0
                                                        (+ acc# (pow (~nrm len# buff# offset# ld#) 2))))
         (= ~norm (long \M)) (let [da# (real-accessor ~a)]
                               (band-storage-reduce ~a len# offset# amax# 0.0
                                                    (let [iamax# (~nrm len# buff# offset# ld#)]
                                                      (max amax# (abs (.get da# buff# (+ offset# (* ld# iamax#))))))))
         :default (dragan-says-ex "This operation has not been implemented for non-square banded matrix.")))
     0.0))

(defmacro sb-lan [lansb norm a]
  `(if (< 0 (.dim ~a))
     (let [stor# (full-storage ~a)
           reg# (region ~a)
           fd# (.fd stor#)]
       (with-release [work# (.createDataSource (data-accessor ~a) fd#)]
         (~lansb ~norm
          (int (if (.isColumnMajor (navigator ~a)) (if (.isLower reg#) \L \U) (if (.isLower reg#) \U \L)))
          fd# (+ (.kl reg#) (.ku reg#)) (.buffer ~a) (.offset ~a) (.ld stor#) work#)))
     0.0))

(defmacro tb-lan [lantb norm a]
  `(if (< 0 (.dim ~a))
     (let [stor# (full-storage ~a)
           reg# (region ~a)
           fd# (.fd stor#)]
       (with-release [work# (.createDataSource (data-accessor ~a) fd#)]
         (~lantb ~norm
          (int (if (.isColumnMajor (navigator ~a)) (if (.isLower reg#) \L \U) (if (.isLower reg#) \U \L)))
          (int (if (.isDiagUnit reg#) \U \N))
          fd# (+ (.kl reg#) (.ku reg#)) (.buffer ~a) (.offset ~a) (.ld stor#) work#)))
     0.0))

(defmacro gb-laset [method alpha a]
  `(let [buff# (.buffer ~a)
         ofst# (.offset ~a)
         ld# (.stride ~a)]
     (band-storage-map ~a len# offset#
                       (with-lapack-check
                         (~method CBLAS/ORDER_ROW_MAJOR (int \g) len# 1 ~alpha ~alpha buff# offset# ld#)))
     ~a))

;;------------------------ Packed Matrix -------------------------------------------

(defmacro sp-lan [lansp norm a]
  `(if (< 0 (.dim ~a))
     (let [stor# (storage ~a)
           reg# (region ~a)
           fd# (.fd stor#)]
       (with-release [work# (.createDataSource (data-accessor ~a) fd#)]
         (~lansp ~norm
          (int (if (.isColumnMajor (navigator ~a)) (if (.isLower reg#) \L \U) (if (.isLower reg#) \U \L)))
          fd# (.buffer ~a) (.offset ~a) work#)))
     0.0))

(defmacro tp-lan [lantp norm a]
  `(if (< 0 (.dim ~a))
     (let [stor# (storage ~a)
           reg# (region ~a)
           fd# (.fd stor#)]
       (with-release [work# (.createDataSource (data-accessor ~a) fd#)]
         (~lantp ~norm
          (int (if (.isColumnMajor (navigator ~a)) (if (.isLower reg#) \L \U) (if (.isLower reg#) \U \L)))
          (int (if (.isDiagUnit reg#) \U \N))
          fd# (.buffer ~a) (.offset ~a) work#)))
     0.0))

(defmacro packed-laset [method alpha a]
  `(let [buff# (.buffer ~a)
         ofst# (.offset ~a)]
     (if-not (.isDiagUnit (region ~a))
       (with-lapack-check
         (~method CBLAS/ORDER_ROW_MAJOR (int \g) (.capacity (storage ~a)) 1 ~alpha ~alpha buff# ofst# 1))
       (dostripe-layout ~a len# idx#
                        (with-lapack-check
                          (~method CBLAS/ORDER_ROW_MAJOR (int \g) len# 1 ~alpha ~alpha
                           buff# (+ ofst# idx#) 1))))
     ~a))

(defmacro packed-lasrt [method a increasing]
  `(let [increasing# (int (if ~increasing \I \D))
         n# (.ncols ~a)
         buff# (.buffer ~a)
         ofst# (.offset ~a)]
     (dostripe-layout ~a len# idx#
                      (with-lapack-check (~method increasing# len# buff# (+ ofst# idx#))))
     ~a))

;; =========== Drivers and Computational LAPACK Routines ===========================

;; ------------- Singular Value Decomposition LAPACK -------------------------------

(defn check-pivot [ipiv]
  (if (= 1 (stride ipiv))
    ipiv
    (dragan-says-ex "You cannot use ipiv with stride different than 1." {:stride (stride ipiv)})))

(defmacro with-sv-check [res expr]
  `(let [info# ~expr]
     (cond
       (= 0 info#) ~res
       (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                   {:arg-index (- info#)}))
       :else (dragan-says-ex "The factorization is singular. The solution could not be computed using this method."
                             {:info info#}))))

;; ============= Solving Systems of Linear Equations ===============================

(defmacro ge-laswp [method a ipiv k1 k2]
  `(do
     (check-pivot ~ipiv)
     (with-sv-check ~a
       (~method (.layout (navigator ~a)) (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a)
        ~k1 ~k2 (.buffer ~ipiv) (.offset ~ipiv) (.stride ~ipiv)))))

(defn matrix-lu [^Matrix a master]
  (let-release [ipiv (create-vector (index-factory a) (min (.mrows a) (.ncols a)) false)]
    (trf (engine a) a ipiv)
    (->LUFactorization a ipiv master (atom true))))

(defn matrix-cholesky [^Matrix a master]
  (trf (engine a) a)
  (->CholeskyFactorization a master (atom true)))

(defn matrix-create-trf [create-fn a pure]
  (if pure
    (let-release [a-copy (raw a)]
      (copy (engine a) a a-copy)
      (create-fn a-copy true))
    (create-fn a false)))

(defmacro ge-trf [method a ipiv]
  `(with-sv-check (check-pivot ~ipiv)
     (~method (.layout (navigator ~a)) (.mrows ~a) (.ncols ~a)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~ipiv) (.offset ~ipiv))))

(defmacro sy-trx
  ([method a ipiv]
   `(let [stor# (full-storage ~a)]
      (check-pivot ~ipiv)
      (with-sv-check ~a
        (~method (.layout (navigator ~a)) (int (if (.isLower (region ~a)) \L \U)) (.ncols ~a)
         (.buffer ~a) (.offset ~a) (.ld stor#) (.buffer ~ipiv) (.offset ~ipiv)))))
  ([method a]
   `(let [stor# (full-storage ~a)]
      (with-sv-check ~a
        (~method (.layout (navigator ~a)) (int (if (.isLower (region ~a)) \L \U)) (.ncols ~a)
         (.buffer ~a) (.offset ~a) (.ld stor#))))))

(defmacro sy-cholesky-lu [method a pure]
  `(if ~pure
     (let-release [a-copy# (raw ~a)]
       (copy (engine ~a) ~a a-copy#)
       (let [info# (~method (.layout (navigator a-copy#)) (int (if (.isLower (region a-copy#)) \L \U))
                    (.ncols ~a) (buffer a-copy#) (offset a-copy#) (stride a-copy#))]
         (cond
           (= 0 info#) (->CholeskyFactorization a-copy# true (atom true))
           (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                       {:arg-index (- info#)}))
           :else (do
                   (copy (engine ~a) ~a a-copy#)
                   (matrix-create-trf matrix-lu a-copy# true)))))
     (matrix-create-trf matrix-lu ~a false)))

(defmacro sp-cholesky-lu [method a pure]
  `(if ~pure
     (let-release [a-copy# (raw ~a)]
       (copy (engine ~a) ~a a-copy#)
       (let [info# (~method (.layout (navigator a-copy#)) (int (if (.isLower (region a-copy#)) \L \U))
                    (.ncols ~a) (buffer a-copy#) (offset a-copy#))]
         (cond
           (= 0 info#) (->CholeskyFactorization a-copy# true (atom true))
           (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                       {:arg-index (- info#)}))
           :else (do
                   (copy (engine ~a) ~a a-copy#)
                   (matrix-create-trf matrix-lu a-copy# true)))))
     (matrix-create-trf matrix-lu ~a false)))

(defn check-gb-submatrix [a]
  (let [reg (region a)
        stor (storage a)]
    (if (.isColumnMajor (navigator a))
      (if (<= (inc (+ (* 2 (.kl reg)) (.ku reg))) (.ld ^BandStorage stor))
        a
        (dragan-says-ex "Banded factorizations require additional kl superdiagonals. Supply a subband."))
      (dragan-says-ex "Banded factorizations are available only for column-major matrices due to a bug in MKL."))))

(defmacro gb-trf [method a ipiv]
  `(let [reg# (region ~a)]
     (check-gb-submatrix ~a)
     (with-sv-check (check-pivot ~ipiv)
       (~method (.layout (navigator ~a)) (.mrows ~a) (.ncols ~a) (.kl reg#) (.ku reg#)
        (.buffer ~a) (- (.offset ~a) (.kl reg#)) (.stride ~a) (.buffer ~ipiv) (.offset ~ipiv)))))

(defmacro sb-trf
  ([method a]
   `(let [reg# (region ~a)]
      (with-sv-check ~a
        (~method CBLAS/ORDER_COLUMN_MAJOR (int \L)
         (.ncols ~a) (+ (.kl reg#) (.ku reg#)) (.buffer ~a) (.offset ~a) (.stride ~a))))))

(defmacro ge-tri [method a ipiv]
  `(let [stor# (full-storage ~a)]
     (check-pivot ~ipiv)
     (with-sv-check ~a
       (~method (.layout (navigator ~a)) (.sd stor#)
        (.buffer ~a) (.offset ~a) (.ld stor#) (.buffer ~ipiv) (.offset ~ipiv)))))

(defmacro tr-tri [method a]
  `(let [stor# (full-storage ~a)
         reg# (region ~a)]
     (~method (.layout (navigator ~a))
      (int (if (.isLower reg#) \L \U)) (int (if (.isDiagUnit reg#) \U \N))
      (.sd stor#) (.buffer ~a) (.offset ~a) (.ld stor#))
     ~a))

(defmacro sp-tri [method a ipiv]
  `(let [stor# (full-storage ~a)
         reg# (region ~a)]
     (~method (.layout (navigator ~a)) (int (if (.isLower reg#) \L \U))
      (.ncols ~a) (.buffer ~a) (.offset ~a) (.buffer ~ipiv) (.offset ~ipiv))
     ~a))

(defmacro tp-tri [method a]
  `(let [stor# (full-storage ~a)
         reg# (region ~a)]
     (~method (.layout (navigator ~a)) (int (if (.isLower reg#) \L \U))
      (int (if (.isDiagUnit reg#) \U \N)) (.ncols ~a) (.buffer ~a) (.offset ~a))
     ~a))

(defmacro ge-trs [method lu b ipiv]
  `(let [nav-b# (navigator ~b)]
     (check-pivot ~ipiv)
     (with-sv-check ~b
       (~method (.layout nav-b#) (int (if (= nav-b# (navigator ~lu)) \N \T))
        (.mrows ~b) (.ncols ~b) (.buffer ~lu) (.offset ~lu) (.stride ~lu)
        (.buffer ~ipiv) (.offset ~ipiv) (.buffer ~b) (.offset ~b) (.stride ~b)))))

(defmacro gb-trs
  ([method lu b ipiv]
   `(let [nav-b# (navigator ~b)
          reg# (region ~lu)]
      (check-gb-submatrix ~lu)
      (check-pivot ~ipiv)
      (with-sv-check ~b
        (~method (.layout nav-b#) (int (if (= nav-b# (navigator ~lu)) \N \T))
         (.mrows ~b) (.kl reg#) (.ku reg#) (.ncols ~b)
         (.buffer ~lu) (- (.offset ~lu) (.kl reg#)) (.stride ~lu)
         (.buffer ~ipiv) (.offset ~ipiv) (.buffer ~b) (.offset ~b) (.stride ~b))))))

(defmacro sb-trs [method a b]
  `(let [nav-b# (navigator ~b)
         reg# (region ~a)]
     (if (.isColumnMajor nav-b#)
       (with-sv-check ~b
         (~method CBLAS/ORDER_COLUMN_MAJOR (int \L)
          (.mrows ~b) (+ (.kl reg#) (.ku reg#)) (.ncols ~b)
          (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))
       (dragan-says-ex "SB solver requires that the right hand matrix have column layout."
                       {:a (info ~a) :b (info ~b)}))))

(defmacro tb-trs [method a b]
  `(let [nav-b# (navigator ~b)
         reg# (region ~a)]
     (with-sv-check ~b
       (~method (.layout nav-b#) (int (if (.isLower reg#) \L \U))
        (int (if (= nav-b# (navigator ~a)) \N \T)) (int (if (.isDiagUnit reg#) \U \N))
        (.mrows ~b) (+ (.kl reg#) (.ku reg#)) (.ncols ~b)
        (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))))

(defmacro tr-trs [method a b]
  `(let [reg-a# (region ~a)
         nav-b# (navigator ~b)]
     (~method (.layout nav-b#)
      (int (if (.isLower reg-a#) \L \U)) (int (if (= nav-b# (navigator ~a)) \N \T))
      (int (if (.isDiagUnit reg-a#) \U \N)) (.mrows ~b) (.ncols ~b)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b))
     ~b))

(defmacro tp-trs [method a b]
  `(let [reg-a# (region ~a)
         nav-b# (navigator ~b)]
     (~method (.layout nav-b#)
      (int (if (.isLower reg-a#) \L \U)) (int (if (= nav-b# (navigator ~a)) \N \T))
      (int (if (.isDiagUnit reg-a#) \U \N)) (.mrows ~b) (.ncols ~b)
      (.buffer ~a) (.offset ~a) (.buffer ~b) (.offset ~b) (.stride ~b))
     ~b))

(defmacro sp-trs
  ([method ldl b ipiv]
   `(let [nav-b# (navigator ~b)]
      (check-pivot ~ipiv)
      (if (= nav-b# (navigator ~ldl))
        (with-sv-check ~b
          (~method (.layout nav-b#) (int (if (.isLower (region ~ldl)) \L \U)) (.mrows ~b) (.ncols ~b)
           (.buffer ~ldl) (.offset ~ldl) (.buffer ~ipiv) (.offset ~ipiv)
           (.buffer ~b) (.offset ~b) (.stride ~b)))
        (dragan-says-ex "SP pivoted solver (trs only) requires that both matrices have the same layout."
                        {:ldl (info ~ldl) :b (info ~b)}))))
  ([method a b]
   `(let [reg-a# (region ~a)
          nav-b# (navigator ~b)
          nav# (int (if (= nav-b# (navigator ~a)) (if (.isLower reg-a#) \L \U) (if (.isLower reg-a#) \U \L)))]
      (~method (.layout nav-b#) nav#
       (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.buffer ~b) (.offset ~b) (.stride ~b))
      ~b)))

(defmacro sp-trx
  ([method a ipiv]
   `(do
      (check-pivot ~ipiv)
      (with-sv-check ~a
        (~method (.layout (navigator ~a)) (int (if (.isLower (region ~a)) \L \U)) (.ncols ~a)
         (.buffer ~a) (.offset ~a) (buffer ~ipiv) (offset ~ipiv)))))
  ([method a]
   `(do
      (with-sv-check ~a
        (~method (.layout (navigator ~a)) (int (if (.isLower (region ~a)) \L \U)) (.ncols ~a)
         (.buffer ~a) (.offset ~a))))))

(defmacro sy-trs
  ([method ldl b ipiv]
   `(let [nav-b# (navigator ~b)]
      (check-pivot ~ipiv)
      (if (= nav-b# (navigator ~ldl))
        (with-sv-check ~b
          (~method (.layout nav-b#) (int (if (.isLower (region ~ldl)) \L \U)) (.mrows ~b) (.ncols ~b)
           (.buffer ~ldl) (.offset ~ldl) (.stride ~ldl) (.buffer ~ipiv) (.offset ~ipiv)
           (.buffer ~b) (.offset ~b) (.stride ~b)))
        (dragan-says-ex "SY pivoted solver (trs only) requires that both matrices have the same layout."
                        {:ldl (info ~ldl) :b (info ~b)}))))
  ([method gg b]
   `(let [nav-b# (navigator ~b)
          uplo# (if (= nav-b# (navigator ~gg))
                  (int (if (.isLower (region ~gg)) \L \U))
                  (int (if (.isLower (region ~gg)) \U \L)))]
      (with-sv-check ~b
        (~method (.layout (navigator ~b)) uplo#
         (.mrows ~b) (.ncols ~b) (.buffer ~gg) (.offset ~gg) (.stride ~gg)
         (.buffer ~b) (.offset ~b) (.stride ~b))))))

(defmacro ge-con [method lu nrm nrm1?]
  `(with-release [da# (real-accessor ~lu)
                  res# (.createDataSource da# 1)]
     (with-sv-check (.get da# res# 0)
       (~method (.layout (navigator ~lu)) (int (if ~nrm1? \O \I))
        (min (.mrows ~lu) (.ncols ~lu))
        (.buffer ~lu) (.offset ~lu) (.stride ~lu) ~nrm res#))))

(defmacro gb-con [method lu ipiv nrm nrm1?]
  `(with-release [da# (real-accessor ~lu)
                  reg# (region ~lu)
                  res# (.createDataSource da# 1)]
     (check-gb-submatrix ~lu)
     (check-pivot ~ipiv)
     (with-sv-check (.get da# res# 0)
       (~method (.layout (navigator ~lu)) (int (if ~nrm1? \O \I))
        (min (.mrows ~lu) (.ncols ~lu)) (.kl reg#) (.ku reg#)
        (.buffer ~lu) (- (.offset ~lu) (.kl reg#)) (.stride ~lu)
        (.buffer ~ipiv) (.offset ~ipiv) ~nrm res#))))

(defmacro sb-con [method gg nrm]
  `(let [da# (real-accessor ~gg)
         reg# (region ~gg)
         res# (.createDataSource da# 1)]
     (with-sv-check (.get da# res# 0)
       (~method CBLAS/ORDER_COLUMN_MAJOR (int \L)
        (.ncols ~gg) (+ (.kl reg#) (.ku reg#)) (.buffer ~gg) (.offset ~gg) (.stride ~gg) ~nrm res#))))

(defmacro tb-con [method a nrm1?]
  `(let [da# (real-accessor ~a)
         reg# (region ~a)
         res# (.createDataSource da# 1)]
     (with-sv-check (.get da# res# 0)
       (~method (.layout (navigator ~a)) (int (if ~nrm1? \O \I))
        (int (if (.isLower reg#) \L \U)) (int (if (.isDiagUnit reg#) \U \N))
        (.ncols ~a) (+ (.kl reg#) (.ku reg#)) (.buffer ~a) (.offset ~a) (.stride ~a) res#))))

(defmacro tr-con [method a nrm1?]
  `(with-release [da# (real-accessor ~a)
                  res# (.createDataSource da# 1)
                  reg# (region ~a)]
     (with-sv-check (.get da# res# 0)
       (~method (.layout (navigator ~a)) (int (if ~nrm1? \O \I))
        (int (if (.isLower reg#) \L \U)) (int (if (.isDiagUnit reg#) \U \N))
        (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a) res#))))

(defmacro tp-con [method a nrm1?]
  `(with-release [da# (real-accessor ~a)
                  res# (.createDataSource da# 1)
                  reg# (region ~a)]
     (with-sv-check (.get da# res# 0)
       (~method (.layout (navigator ~a)) (int (if ~nrm1? \O \I))
        (int (if (.isLower reg#) \L \U)) (int (if (.isDiagUnit reg#) \U \N))
        (.ncols ~a) (.buffer ~a) (.offset ~a) res#))))

(defmacro sy-con
  ([method ldl ipiv nrm]
   `(with-release [da# (real-accessor ~ldl)
                   res# (.createDataSource da# 1)]
      (with-sv-check (.get da# res# 0)
        (~method (.layout (navigator ~ldl)) (int (if (.isLower (region ~ldl)) \L \U)) (.ncols ~ldl)
         (.buffer ~ldl) (.offset ~ldl) (.stride ~ldl) (.buffer ~ipiv) (.offset ~ipiv) ~nrm res#))))
  ([method gg nrm]
   `(with-release [da# (real-accessor ~gg)
                   res# (.createDataSource da# 1)]
      (with-sv-check (.get da# res# 0)
        (~method (.layout (navigator ~gg)) (int (if (.isLower (region ~gg)) \L \U)) (.ncols ~gg)
         (.buffer ~gg) (.offset ~gg) (.stride ~gg) ~nrm res#)))))

(defmacro sp-con
  ([method ldl ipiv nrm]
   `(with-release [da# (real-accessor ~ldl)
                   res# (.createDataSource da# 1)]
      (with-sv-check (.get da# res# 0)
        (~method (.layout (navigator ~ldl)) (int (if (.isLower (region ~ldl)) \L \U)) (.ncols ~ldl)
         (.buffer ~ldl) (.offset ~ldl) (.buffer ~ipiv) (.offset ~ipiv) ~nrm res#))))
  ([method gg nrm]
   `(with-release [da# (real-accessor ~gg)
                   res# (.createDataSource da# 1)]
      (with-sv-check (.get da# res# 0)
        (~method (.layout (navigator ~gg)) (int (if (.isLower (region ~gg)) \L \U)) (.ncols ~gg)
         (.buffer ~gg) (.offset ~gg) ~nrm res#)))))

(defmacro ge-sv
  ([method a b pure]
   `(if ~pure
      (with-release [a# (create-ge (factory ~a) (.mrows ~a) (.ncols ~a) (.isColumnMajor (navigator ~b)) false)]
        (copy (engine ~a) ~a a#)
        (sv (engine ~a) a# ~b false))
      (let [nav-b# (navigator ~b)]
        (if (= (navigator ~a) nav-b#)
          (with-release [ipiv# (create-vector (index-factory ~a) (.ncols ~a) false)]
            (check-pivot ipiv#)
            (with-sv-check ~b
              (~method (.layout nav-b#) (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.stride ~a)
               (buffer ipiv#) (offset ipiv#) (.buffer ~b) (.offset ~b) (.stride ~b))))
          (dragan-says-ex "GE solver requires that both matrices have the same layout."
                          {:a (info ~a) :b (info ~b)}))))))

(defmacro gb-sv
  ([method a b pure]
   `(let [reg# (region ~a)
          nav-a# (navigator ~a)
          nav-b# (navigator ~b)]
      (if ~pure
        (with-release [a# (create-gb (factory ~a) (.mrows ~a) (.ncols ~a) (.kl reg#) (.ku reg#)
                                     (.isColumnMajor nav-b#) false)]
          (copy (engine ~a) ~a a#)
          (sv (engine ~a) a# ~b false))
        (if (= nav-b# nav-a#)
          (with-release [ipiv# (create-vector (index-factory ~a) (.ncols ~a) false)]
            (check-gb-submatrix ~a)
            (check-pivot ipiv#)
            (with-sv-check ~b
              (~method (.layout nav-b#) (.mrows ~b) (.kl reg#) (.ku reg#) (.ncols ~b)
               (.buffer ~a) (- (.offset ~a) (.kl reg#)) (.stride ~a) (buffer ipiv#) (offset ipiv#)
               (.buffer ~b) (.offset ~b) (.stride ~b))))
          (dragan-says-ex "GB solver requires that both matrices have the same layout."
                          {:a (info ~a) :b (info ~b)})))
      ~b)))

(defmacro sb-sv [method a b pure]
  `(let [reg# (region ~a)
         nav-b# (navigator ~b)]
     (if ~pure
       (with-release [a# (raw ~a)]
         (copy (engine ~a) ~a a#)
         (sv (engine ~a) a# ~b false))
       (if (.isColumnMajor nav-b#)
         (with-sv-check ~b
           (~method CBLAS/ORDER_COLUMN_MAJOR (int \L)
            (.mrows ~b) (+ (.kl reg#) (.ku reg#)) (.ncols ~b)
            (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b)))
         (dragan-says-ex "SB solver requires that the right hand matrix have column layout."
                         {:a (info ~a) :b (info ~b)})))
     ~b))

(defmacro sy-sv
  ([po-method sy-method a b pure]
   `(let [nav-b# (navigator ~b)
          uplo# (if (= nav-b# (navigator ~a))
                  (int (if (.isLower (region ~a)) \L \U))
                  (int (if (.isLower (region ~a)) \U \L)))]
      (if ~pure
        (with-release [a# (raw ~a)]
          (copy (engine ~a) ~a a#)
          (let [info# (~po-method (.layout nav-b#) uplo# (.mrows ~b) (.ncols ~b)
                       (buffer a#) (offset a#) (stride a#) (.buffer ~b) (.offset ~b) (.stride ~b))]
            (cond
              (= 0 info#) ~b
              (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                          {:arg-index (- info#)}))
              :else (do
                      (copy (engine ~a) ~a a#)
                      (sv (engine ~a) a# ~b false)))))
        (with-release [ipiv# (create-vector (index-factory ~a) (.ncols ~a) false)]
          (check-pivot ipiv#)
          (with-sv-check ~b
            (~sy-method (.layout nav-b#) uplo# (.mrows ~b) (.ncols ~b)
             (.buffer ~a) (.offset ~a) (.stride ~a) (buffer ipiv#) (offset ipiv#)
             (.buffer ~b) (.offset ~b) (.stride ~b)))))))
  ([method a b]
   `(let [nav-b# (navigator ~b)
          uplo# (if (= nav-b# (navigator ~a))
                  (int (if (.isLower (region ~a)) \L \U))
                  (int (if (.isLower (region ~a)) \U \L)))]
      (with-sv-check ~b
        (~method (.layout nav-b#) uplo# (.mrows ~b) (.ncols ~b)
         (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b))))))

(defmacro sp-sv
  ([po-method sp-method a b pure]
   `(let [nav-b# (navigator ~b)
          uplo# (if (= nav-b# (navigator ~a))
                  (int (if (.isLower (region ~a)) \L \U))
                  (int (if (.isLower (region ~a)) \U \L)))]
      (if ~pure
        (with-release [a# (raw ~a)]
          (copy (engine ~a) ~a a#)
          (let [info# (~po-method (.layout nav-b#) uplo# (.mrows ~b) (.ncols ~b)
                       (buffer a#) (offset a#) (.buffer ~b) (.offset ~b) (.stride ~b))]
            (cond
              (= 0 info#) ~b
              (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                          {:arg-index (- info#)}))
              :else (do
                      (copy (engine ~a) ~a a#)
                      (sv (engine ~a) a# ~b false)))))
        (with-release [ipiv# (create-vector (index-factory ~a) (.ncols ~a) false)]
          (check-pivot ipiv#)
          (with-sv-check ~b
            (~sp-method (.layout nav-b#) uplo# (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a)
             (buffer ipiv#) (offset ipiv#) (.buffer ~b) (.offset ~b) (.stride ~b)))))))
  ([method a b]
   `(let [nav-b# (navigator ~b)
          uplo# (if (= nav-b# (navigator ~a))
                  (int (if (.isLower (region ~a)) \L \U))
                  (int (if (.isLower (region ~a)) \U \L)))]
      (with-sv-check ~b
        (~method (.layout nav-b#) uplo#
         (.mrows ~b) (.ncols ~b) (.buffer ~a) (.offset ~a) (.buffer ~b) (.offset ~b) (.stride ~b))))))

;; ------------- Orthogonal Factorization (L, Q, R) LAPACK -------------------------------

(defmacro with-lqr-check [tau res expr]
  `(if (= 1 (.stride ~tau))
     (let [info# ~expr]
       (if (= 0 info#)
         ~res
         (throw (ex-info "There has been an illegal argument in the native function call."
                         {:arg-index (- info#)}))))
     (throw (ex-info "You cannot use tau with stride different than 1." {:stride (.stride ~tau)}))))

(defmacro ge-lqrf [method a tau]
  `(with-lqr-check ~tau ~tau
     (~method (.layout (navigator ~a)) (.mrows ~a) (.ncols ~a)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~tau) (.offset ~tau))))

(defmacro or-glqr [method a tau]
  `(with-lqr-check ~tau ~a
     (~method (.layout (navigator ~a)) (.mrows ~a) (.ncols ~a) (.dim ~tau)
      (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~tau) (.offset ~tau))))

(defmacro or-mlqr [method a tau c left]
  `(with-lqr-check ~tau ~c
     (~method (.layout (navigator ~c)) (int (if ~left \L \R))
      (int (if (= (navigator ~a) (navigator ~c)) \N \T)) (.mrows ~c) (.ncols ~c) (.dim ~tau)
      (.buffer ~a) (.offset ~a) (.stride ~a)
      (.buffer ~tau) (.offset ~tau) (.buffer ~c) (.offset ~c) (.stride ~c))))

;; ------------- Linear Least Squares Routines LAPACK -------------------------------

(defmacro ge-ls [method a b]
  `(let [nav# (navigator ~a)
         info# (~method (.layout nav#) (int (if (= nav# (navigator ~b)) \N \T))
                (.mrows ~a) (.ncols ~a) (.ncols ~b)
                (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~b) (.offset ~b) (.stride ~b))]
     (cond
       (= 0 info#) ~b
       (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                   {:arg-index (- info#)}))
       :else (throw (ex-info "The i-th diagonal element of a is zero, so the matrix does not have full rank."
                             {:arg-index info#})))))

;; ------------- Non-Symmetric Eigenvalue Problem Routines LAPACK -------------------------------

(defmacro ge-ev [method a w vl vr]
  `(if (.isColumnMajor (navigator ~w))
     (let [wr# (.col ~w 0)
           wi# (.col ~w 1)
           info# (~method (.layout (navigator  ~a))
                  (int (if (< 0 (.mrows ~vl)) \V \N)) (int (if (< 0 (.mrows ~vr)) \V \N))
                  (.ncols ~a) (.buffer ~a) (.offset ~a) (.stride ~a)
                  (buffer wr#) (offset wr#) (buffer wi#) (offset wi#)
                  (.buffer ~vl) (.offset ~vl) (.stride ~vl) (.buffer ~vr) (.offset ~vr) (.stride ~vr))]
       (cond
         (= 0 info#) ~w
         (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                     {:arg-index (- info#)}))
         :else (throw (ex-info "The QR algorithm failed to compute all the eigenvalues."
                               {:first-converged (inc info#)}))))
     (throw (ex-info "You cannot use w that is not column-oriented and has less than 2 columns."
                     {:column-major? (.isColumnMajor (navigator  ~w)) :ncols (.ncols ~w)}))))

;; ------------- Singular Value Decomposition Routines LAPACK -------------------------------

(defmacro with-svd-check [s expr]
  `(let [info# ~expr]
     (cond
       (= 0 info#) ~s
       (< info# 0) (throw (ex-info "There has been an illegal argument in the native function call."
                                   {:arg-index (- info#)}))
       :else (throw (ex-info "The reduction to bidiagonal form did not converge"
                             {:non-converged-superdiagonals info#})))))

(defmacro ge-svd
  ([method a sigma u vt superb]
   `(if (and (.isGapless (storage ~sigma)) (.isGapless (storage ~superb)))
      (let [m# (.mrows ~a)
            n# (.ncols ~a)]
        (with-svd-check ~sigma
          (~method (.layout (navigator  ~a))
           (int (cond (= m# (.mrows ~u) (.ncols ~u)) \A
                      (and (= m# (.mrows ~u)) (= (min m# n#) (.ncols ~u))) \S
                      (nil? ~u) \O
                      :else \N))
           (int (cond (= n# (.mrows ~vt) (.ncols ~vt)) \A
                      (and (= (min m# n#) (.mrows ~vt)) (= n# (.ncols ~vt))) \S
                      (and ~u (nil? ~vt)) \O
                      :else \N))
           m# n# (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~sigma) (.offset ~sigma)
           (.buffer ~u) (.offset ~u) (.stride ~u) (.buffer ~vt) (.offset ~vt) (.stride ~vt)
           (.buffer ~superb) (.offset ~superb))))
      (dragan-says-ex "\u01a9 and superb have to be diagonal banded matrices."
                      {:s (info ~sigma) :superb (info ~superb)})))
  ([method a sigma zero-uvt superb]
   `(if (and (.isGapless (storage ~sigma)) (.isGapless (storage ~superb)))
      (with-svd-check ~sigma
        (~method (.layout (navigator  ~a)) (int \N) (int \N) (.mrows ~a) (.ncols ~a)
         (.buffer ~a) (.offset ~a) (.stride ~a) (.buffer ~sigma) (.offset ~sigma)
         (.buffer ~zero-uvt) (.offset ~zero-uvt) (.stride ~zero-uvt)
         (.buffer ~zero-uvt) (.offset ~zero-uvt) (.stride ~zero-uvt)
         (.buffer ~superb) (.offset ~superb)))
      (dragan-says-ex "\u01a9 and superb have to be diagonal banded matrices."
                      {:s (info ~sigma) :superb (info ~superb)}))))
