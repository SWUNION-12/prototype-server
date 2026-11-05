FROM codercom/code-server:4.144.0

# uv 바이너리만 복사한다. 이미지 안에서 pip로 설치하지 않는다.
COPY --from=ghcr.io/astral-sh/uv:0.11.29 /uv /uvx /usr/local/bin/

# 수업 이미지 빌드(root)와 학생 실행(coder)이 같은 경로를 보도록 이미지 레벨에서 고정한다.
ENV UV_PYTHON_INSTALL_DIR=/opt/uv-python \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_CACHE_DIR=/opt/uv-cache \
    UV_LINK_MODE=copy \
    UV_PYTHON_PREFERENCE=only-managed \
    PATH=/opt/venv/bin:$PATH

USER root
# /opt/lab, /opt/uv-python: root 소유(학생은 읽기만). /opt/uv-cache: coder 소유(복구 때 coder가 uv를 실행).
# /opt/venv는 수업 이미지 빌드에서 만들고 chown 한다.
RUN mkdir -p /opt/lab /opt/uv-python /opt/uv-cache \
    && chown coder:coder /opt/uv-cache \
    && chmod 755 /opt/lab /opt/uv-python

# 빈 볼륨을 처음 마운트하면 Docker가 이 내용을 볼륨으로 복사한다.
COPY --chown=coder:coder project/ /home/coder/project/
RUN mkdir -p /home/coder/.local/share/code-server/User && chown -R coder:coder /home/coder/.local
COPY --chown=coder:coder settings.json /home/coder/.local/share/code-server/User/settings.json
RUN echo 'export PATH=/opt/venv/bin:$PATH' >> /home/coder/.bashrc && chown coder:coder /home/coder/.bashrc

USER coder
# Open VSX 사정으로 실패할 수 있다. 실패해도 빌드는 계속하고 로그에 남긴다.
RUN code-server --install-extension ms-python.python \
    || echo "WARN: ms-python.python install failed"

# 실습 도우미 확장. 빌드 단계 없는 소스를 vsix(zip)로 묶어 설치한다. 묶을 때는 code-server에 든 node를 쓰므로
# 네트워크가 필요 없다. ms-python 설치 뒤에 두어 확장만 바뀌면 이 아래 층만 다시 만든다. 설치에 실패하면 빌드를 멈춘다.
COPY --chown=coder:coder extension/ /tmp/swunion-extension/
RUN /usr/lib/code-server/lib/node /tmp/swunion-extension/pack-vsix.js \
        /tmp/swunion-extension/swunion-helper /tmp/swunion-helper.vsix \
    && code-server --install-extension /tmp/swunion-helper.vsix \
    && code-server --list-extensions | grep -qx 'swunion.swunion-helper' \
    && rm -rf /tmp/swunion-extension /tmp/swunion-helper.vsix

# 활동 막대에서 Source Control, Run and Debug, Extensions, Testing 아이콘을 숨긴다(설명은 스크립트 머리말).
# code-server 설치 파일을 고치므로 root로 실행한다. 대상 파일이 없으면 빌드가 멈춘다. 맨 아래 층이라 위 층 캐시를 깨지 않는다.
USER root
COPY hide-activity-icons.sh /tmp/hide-activity-icons.sh
RUN sh /tmp/hide-activity-icons.sh && rm -f /tmp/hide-activity-icons.sh
USER coder

CMD ["--bind-addr","0.0.0.0:8080","--auth","password","--disable-telemetry","--disable-update-check","/home/coder/project"]
