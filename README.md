##BXH Ugly simple LDB

## Chạy dev với hot reload (refresh browser)
```shell script
./mvnw compile quarkus:dev
```

## Packaging and running the application
```shell script
./mvnw package
```

## Đóng gói jar
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

## Đóng gói binary (5x lower memory footprint, khuyến nghị cho prod)
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```