#!/bin/sh

POD="$(kubectl get pod | grep default-sso | grep deployment | cut -d ' ' -f 1)"

echo "> Running proxy on port 8080 against pod \"$POD\""
kubectl port-forward "$POD" 8080:8080 --address="127.0.0.1"
