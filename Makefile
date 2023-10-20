
IMAGE="camunda/zeebe"
TAG="0.26.7"

build:
	docker build --no-cache --build-arg DISTBALL=dist/target/zeebe-distribution-0.26.6.tar.gz -t ${IMAGE}:${TAG} --target app .
