
IMAGE="camunda/zeebe"
TAG="0.26.0.1"

build:
	docker build --no-cache --build-arg DISTBALL=dist/target/zeebe-distribution-0.26.0.tar.gz -t ${IMAGE}:${TAG} --target app .
