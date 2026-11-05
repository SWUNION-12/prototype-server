#!/bin/sh
# 기본 이미지 빌드. 프로젝트 루트 어디서 실행해도 된다.
# :2부터 "실습 도우미" 확장이 들어 있다. 예전 수업 이미지가 기대는 swunion/lab-base:1은 지우거나 덮어쓰지 않는다.
set -eu
cd "$(dirname "$0")/.."
docker build -t swunion/lab-base:2 docker/lab-base
