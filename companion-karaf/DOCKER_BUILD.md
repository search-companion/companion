# Generate docker image
### Set environment
```
export IMAGE_NAME=searchcompanion/karaf
export BUILD_DIR=../../target/checkout/companion-karaf/target
```
### Cleanup previous image (optional)
```
docker rmi "${IMAGE_NAME}"
```
### Generate karaf tar.gz
```
mvn clean release:prepare release:perform
```
### Generate docker image
```
cd ./create-docker-image
./build.sh --from-local-dist --archive ${BUILD_DIR}/companion-karaf-*.tar.gz --image-name "${IMAGE_NAME}"
```
### Push to docker hub
```
docker push "${IMAGE_NAME}"
```