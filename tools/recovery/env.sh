#!/usr/bin/env bash
# Source for each execution: managed proxy ports may differ between executions.
: "${NATIVE_TOOLCHAIN_ROOT:?Set an absolute path outside the repository for local tools}"
[[ "$NATIVE_TOOLCHAIN_ROOT" == /* ]] || { echo "NATIVE_TOOLCHAIN_ROOT must be absolute" >&2; return 1; }
export NATIVE_TOOLCHAIN_ROOT
export JAVA_HOME="$NATIVE_TOOLCHAIN_ROOT/jdk17"
export ANDROID_HOME="$NATIVE_TOOLCHAIN_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$NATIVE_TOOLCHAIN_ROOT/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="$NATIVE_TOOLCHAIN_ROOT/gradle-home"
export PATH="$NATIVE_TOOLCHAIN_ROOT/bin:$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"
export NATIVE_HEAVY_LOCK="$NATIVE_TOOLCHAIN_ROOT/../heavy-gates.lock"
_native_java_env="$(python3 - <<'PY'
import os, shlex, urllib.parse
keys=('http.proxyHost','http.proxyPort','https.proxyHost','https.proxyPort','http.nonProxyHosts','https.nonProxyHosts','socksProxyHost','socksProxyPort','javax.net.ssl.trustStore')
clean={}
for key in ['GRADLE_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:
    clean[key]=[s for s in shlex.split(os.environ.get(key,'')) if not any(s.startswith('-D'+p+'=') for p in keys)]
proxy=os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or os.environ.get('HTTP_PROXY') or os.environ.get('http_proxy')
if proxy:
    u=urllib.parse.urlsplit(proxy)
    if u.scheme not in ('http','https') or not u.hostname or u.username or u.password:
        raise SystemExit('Unsupported managed proxy format; no automatic fallback')
    port=u.port or (443 if u.scheme=='https' else 80)
    clean['JAVA_TOOL_OPTIONS'] += [f'-Dhttp.proxyHost={u.hostname}',f'-Dhttp.proxyPort={port}',f'-Dhttps.proxyHost={u.hostname}',f'-Dhttps.proxyPort={port}', '-Dhttp.nonProxyHosts=localhost|127.*|[::1]']
clean['JAVA_TOOL_OPTIONS'] += ['-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts']
for key, values in clean.items():
    print('export '+key+'='+shlex.quote(' '.join(values)))
PY
)" || { unset _native_java_env; return 1; }
eval "$_native_java_env"
unset _native_java_env
