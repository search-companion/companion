# Generate docker image
### Set environment
```
export IMAGE_NAME=searchcompanion/karaf
```
### Cleanup previous image (optional)
```
docker rmi "${IMAGE_NAME}"
```
### Generate karaf tar.gz
```
mvn clean install
```
### Generate docker image
```
cd ./create-docker-image
./build.sh --from-local-dist --archive ../target/companion-karaf-*.tar.gz --image-name "${IMAGE_NAME}"
```
### Push to docker hub
```
docker push "${IMAGE_NAME}"
```