#!/bin/sh
openssl ecparam -name sect571k1 -genkey -noout -out tokens.key.pem
openssl req -new -key tokens.key.pem -x509 -nodes -days 365 -out tokens-cert.pem
openssl pkcs12 -export -in tokens-cert.pem -inkey tokens.key.pem -name access-token -out keystore.p12

keytool -importkeystore -v -trustcacerts -srckeystore keystore.p12 -srcstoretype PKCS12 -destkeystore keystore.jceks -deststoretype JCEKS
keytool -genseckey -alias code-signature-key -keyalg aes -keysize 256 -keystore keystore.jceks -storetype JCEKS
#keytool -genkey -alias access-token -keyalg ECDSA -validity 1825 -sigalg SHA256withECDSA -keystore "test.jks" -storetype JKS -dname "CN=test-certificate,C=UA"