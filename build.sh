#!/bin/bash
mkdir -p ~/diplay-auth/offline-mfi && cd /tmp && gh release download v0.2.13 -R shihabal3amri/DiPlay -p DiPlay-0.2.13.apk --clobber && unzip -j -o DiPlay-0.2.13.apk assets/offline-mfi/identity.pk8 assets/offline-mfi/certificate.p7b -d ~/diplay-auth/offline-mfi
cd /code/work/DiPlay && DIPLAY_AUTH_ASSETS_DIR=~/diplay-auth ANDROID_KEYSTORE_PATH=/root/diplay-release/release.jks ANDROID_KEYSTORE_PASSWORD=diplay6pass ANDROID_KEY_ALIAS=diplay ANDROID_KEY_PASSWORD=diplay6pass ./gradlew :mobile:assembleRelease --max-workers=1

ls -l mobile/build/outputs/apk/release/mobile-release.apk
