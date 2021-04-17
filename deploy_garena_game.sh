./mvnw clean package
docker build -f src/main/docker/Dockerfile.jvm -t ecrafteventagency/garena_game .
docker tag ecrafteventagency/garena_game:latest ecrafteventagency/garena_game:1.0
docker push ecrafteventagency/garena_game:1.0
kubectl apply -f src/main/kubefiles/garena-game.yml
#kubectl rollout restart deployment/garena-game