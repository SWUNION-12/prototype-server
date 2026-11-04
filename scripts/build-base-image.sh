#!/bin/sh
# 기본 이미지 빌드. 프로젝트 루트 어디서 실행해도 된다.
set -eu
cd "$(dirname "$0")/.."
docker build -t swunion/lab-base:1 docker/lab-base
