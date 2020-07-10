# GarageDoorServer
This is the Java code that runs on a Raspberry Pi to control my garage door. For the Android app, see GarageDoorApp

This is a NetBeans project.

For the SysV init script, see GarageDoorServerInit

Do a standard NetBeans remote deploy to the Raspberry Pi.

It needs two support libraries:
* Netty from http://netty.io/ for the network server framework
* Pi4J from http://pi4j.com/ which provides GPIO support

For Netty, install netty-buffer-x.x.x.Final.jar, netty-codec-x.x.x.Final.jar, netty-common-x.x.x.Final.jar, netty-handler-x.x.x.Final.jar, and netty-transport-x.x.x.Final.jar

For Pi4J, install pi4j-core.jar, pi4j-device.jar, and pi4j-gpio-extension.jar

Here are the steps to generate two self-signed X.509 certificates. This will generate two new RSA 2048 bit keys, generate two self signed certificates, and bundle the client certificate with the corresponding private key, and the server's public certificate in a PKCS#12 container file. These certificates will be valid for 10 years (3650 days).

```
OPENSSL_OPTS="-new -newkey rsa:2048 -nodes -days 3650 -x509"
openssl req -keyout key-server.pem -subj "/CN=server" -out cert-server.pem $OPENSSL_OPTS
openssl req -keyout key-client.pem -subj "/CN=client" -out cert-client.pem $OPENSSL_OPTS
openssl pkcs12 -export -passout "pass:CorrectHorseBatteryStaple" -in cert-client.pem -inkey key-client.pem -out client.p12 -certfile cert-server.pem -name "Client" -caname "Server"
```

Now you need to install the certificates into the proper places.

1. Copy client.p12 to the sdcard directory on your phone, and use the setup app "Fetch Certificate" button to move it to protected storage.

2. Copy cert-server.pem, key-server.pem, and cert-client.pem to /etc/garagedoor on the Raspberry Pi.

3. Run "adduser garagedoor" (as root) on the Raspberry Pi to create the user that will run the code.

4. Set the proper permissions by running (as root) on the Raspberry Pi:
```
cd /etc/garagedoor
chmod 600 .
chmod 400 cert-server.pem key-server.pem cert-client.pem
chown garagedoor:garagedoor . cert-server.pem key-server.pem cert-client.pem
```
