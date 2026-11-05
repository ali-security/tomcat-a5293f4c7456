#!/usr/bin/env bash
# Sealed-build helper: build OpenSSL 3.5.7 and Tomcat Native 1.3.8 (the version
# pinned by build.properties.default) so the JNI [OpenSSL] and FFM
# [OpenSSL-FFM] test variants, and TestPQC (needs OpenSSL >= 3.5 for ML-KEM),
# run instead of self-skipping against the runner's OpenSSL 3.0.
# Exports LD_LIBRARY_PATH / SEAL_TCNATIVE_LIB / SEAL_OPENSSL_BIN via GITHUB_ENV.
set -euo pipefail

OPENSSL_VERSION=3.5.7
OPENSSL_SHA256=a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8
TCNATIVE_VERSION=1.3.8
TCNATIVE_SHA512=a12b97979037720465300cbc05777ff6ee2ec1dc59ff698864d7068800bf0c2d2606a4e0e29a4d7b16f92c9130604a13e0cc27929a224c9ff77e532ac98a4695

PREFIX="${RUNNER_TEMP}/openssl"
TCN_PREFIX="${RUNNER_TEMP}/tcnative"
WORK="${RUNNER_TEMP}/native-src"
mkdir -p "$WORK"
cd "$WORK"

sudo apt-get update -q
sudo apt-get install -y -q libapr1-dev build-essential

curl -fsSL -o openssl.tar.gz \
  "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"
echo "${OPENSSL_SHA256}  openssl.tar.gz" | sha256sum -c -
tar xzf openssl.tar.gz
( cd "openssl-${OPENSSL_VERSION}" \
  && ./Configure shared no-tests no-docs --prefix="$PREFIX" --libdir=lib \
  && { make -j"$(nproc)" && make install_sw; } > "$WORK/openssl-make.log" 2>&1 \
  || { tail -50 "$WORK/openssl-make.log"; exit 1; } )

curl -fsSL -o tcnative.tar.gz \
  "https://archive.apache.org/dist/tomcat/tomcat-connectors/native/${TCNATIVE_VERSION}/source/tomcat-native-${TCNATIVE_VERSION}-src.tar.gz"
echo "${TCNATIVE_SHA512}  tcnative.tar.gz" | sha512sum -c -
tar xzf tcnative.tar.gz
( cd "tomcat-native-${TCNATIVE_VERSION}-src/native" \
  && ./configure --with-apr=/usr/bin/apr-1-config --with-java-home="$JAVA_HOME" \
       --with-ssl="$PREFIX" --prefix="$TCN_PREFIX" \
  && { make -j"$(nproc)" && make install; } > "$WORK/tcnative-make.log" 2>&1 \
  || { tail -50 "$WORK/tcnative-make.log"; exit 1; } )

"$PREFIX/bin/openssl" version 2>/dev/null || LD_LIBRARY_PATH="$PREFIX/lib" "$PREFIX/bin/openssl" version
ls -l "$TCN_PREFIX/lib"

{
  echo "LD_LIBRARY_PATH=${TCN_PREFIX}/lib:${PREFIX}/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  echo "SEAL_TCNATIVE_LIB=${TCN_PREFIX}/lib"
  echo "SEAL_OPENSSL_BIN=${PREFIX}/bin/openssl"
} >> "$GITHUB_ENV"
