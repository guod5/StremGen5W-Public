#!/usr/bin/env bash
# Build ExoPlayer 2.15.1's FFmpeg ARMv7 audio extension for Android 4.4.
# FFmpeg release 4.2.11 is pinned by its upstream tag commit.
set -euo pipefail

: "${ANDROID_HOME:?Android SDK path must be set}"
NDK="${ANDROID_HOME}/ndk/21.4.7075529"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
SOURCE="${GITHUB_WORKSPACE:-$PWD}/app/src/main/cpp/ffmpeg"
EXPECTED="e98a6be89b5554621ece683c824c475cc44b2195"
if [[ ! -x "${TOOLCHAIN}/armv7a-linux-androideabi19-clang" ]]; then
    echo "Android NDK r21e clang for API 19 missing" >&2
    exit 1
fi
if [[ ! -d "${SOURCE}/.git" ]]; then
    git clone --quiet --depth 1 --branch n4.2.11 \
      https://github.com/FFmpeg/FFmpeg.git "${SOURCE}"
fi
ACTUAL="$(git -C "${SOURCE}" rev-parse HEAD)"
if [[ "${ACTUAL}" != "${EXPECTED}" ]]; then
    echo "FFmpeg source revision is not the pinned release: ${ACTUAL}" >&2
    exit 1
fi

cd "${SOURCE}"
CC="${TOOLCHAIN}/armv7a-linux-androideabi19-clang"
CXX="${TOOLCHAIN}/armv7a-linux-androideabi19-clang++"
LIBDIR="${SOURCE}/android-libs/armeabi-v7a"
mkdir -p "${LIBDIR}"
# ExoPlayer's JNI bridge supports these audio types. This includes
# AC3, Dolby Digital Plus (EAC3), DTS (dca) and TrueHD, plus common
# stereo and lossless codecs. No video codecs are built.
DECODERS=(aac ac3 eac3 dca truehd mp3 flac vorbis opus alac
          pcm_mulaw pcm_alaw amrnb amrwb)
OPTIONS=()
for name in "${DECODERS[@]}"; do OPTIONS+=("--enable-decoder=${name}"); done

./configure \
  --target-os=android \
  --arch=arm \
  --cpu=armv7-a \
  --enable-cross-compile \
  --cc="${CC}" \
  --cxx="${CXX}" \
  --ar="${TOOLCHAIN}/arm-linux-androideabi-ar" \
  --nm="${TOOLCHAIN}/arm-linux-androideabi-nm" \
  --ranlib="${TOOLCHAIN}/arm-linux-androideabi-ranlib" \
  --strip="${TOOLCHAIN}/arm-linux-androideabi-strip" \
  --sysroot="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
  --libdir="${LIBDIR}" \
  --extra-cflags="-fPIC -march=armv7-a -mfloat-abi=softfp" \
  --enable-pic \
  --enable-static \
  --disable-shared \
  --disable-everything \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-asm \
  --disable-network \
  --disable-avdevice \
  --disable-avformat \
  --disable-swscale \
  --disable-avfilter \
  --disable-postproc \
  --disable-symver \
  --disable-iconv \
  --disable-zlib \
  --enable-swresample \
  "${OPTIONS[@]}"

make -j 4
make install-libs
for lib in avcodec avutil swresample; do
    test -s "${LIBDIR}/lib${lib}.a" || {
      echo "Missing native FFmpeg library: ${lib}" >&2
      exit 1
    }
done
echo "FFmpeg audio decoders built for Gen5W ARMv7: ${DECODERS[*]}"
