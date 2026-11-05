#!/bin/sh
# 학생 컨테이너가 EC2 메타데이터(서버의 AWS 권한)에 닿지 못하게 막는다.
iptables -C DOCKER-USER -d 169.254.169.254/32 -j DROP 2>/dev/null || iptables -I DOCKER-USER -d 169.254.169.254/32 -j DROP
# 학생 컨테이너에서 이 서버로는 앱 포트(8080)만 허용한다. 다른 서비스와 포트에는 닿지 못한다.
iptables -N SWUNION-IN 2>/dev/null || iptables -F SWUNION-IN
iptables -A SWUNION-IN -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
iptables -A SWUNION-IN -p tcp --dport 8080 -j RETURN
iptables -A SWUNION-IN -j DROP
iptables -C INPUT -i docker0 -j SWUNION-IN 2>/dev/null || iptables -I INPUT 1 -i docker0 -j SWUNION-IN
iptables -N SWUNION-8080 2>/dev/null || iptables -F SWUNION-8080
iptables -A SWUNION-8080 -i lo -j RETURN
iptables -A SWUNION-8080 -i docker0 -j RETURN
iptables -A SWUNION-8080 -j DROP
iptables -C INPUT -p tcp --dport 8080 -j SWUNION-8080 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 8080 -j SWUNION-8080
