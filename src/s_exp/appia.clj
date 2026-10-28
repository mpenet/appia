(ns s-exp.appia
  "Router utilities for HTTP request handling using matching on path/method.

  Provides functions for building and matching routes that follow the openapi
  path format.

  Exposes:
  - router: Builds a router from a set of routes.
  - match: Attempts to match a router against a Ring-style request."
  (:import (s_exp.appia Router)))

(set! *warn-on-reflection* true)

(defn router
  "Given set of routes, builds router"
  ^Router [routes]
  (Router/create routes))

(defn match
  "Given `matcher` attempts to match against ring request, return match (tuple of
  `data` & `path-params`)"
  [^Router matcher request]
  (.match matcher request))
