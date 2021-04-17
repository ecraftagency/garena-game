package org.acme.resteasy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.util.Random;

@ApplicationScoped
@SuppressWarnings("unused")
public class Resource {
  @ConfigProperty(name = "redis.ldb-name")
  String ldbName;

  @ConfigProperty(name = "quarkus.redis.hosts")
  String        redisConn;
  RedisClient   redisClient;
  RedisReactiveCommands<String, String> rxCommand;

  void serviceStartup(@Observes StartupEvent ev) {
    redisClient = RedisClient.create(redisConn);
    StatefulRedisConnection<String, String> connection = redisClient.connect();
    rxCommand = connection.reactive();
  }

  @Route(methods = HttpMethod.GET, regex = ".*/newgame")
  void newGame(RoutingContext ctx) {
    String id  = ctx.request().getParam("id");
    long curMs = System.currentTimeMillis();
    rxCommand.get("turn:" + id)
      .doOnSuccess(s -> {
        try {
          String[] turnInfo = s.split(":");
          if (turnInfo.length != 2)
            throw new Exception("corrupt data");

          int turn = Integer.parseInt(turnInfo[0]);
          long lastRequest = Long.parseLong(turnInfo[1]);
          if (curMs - lastRequest > 60*1000)
            updateTurn(ctx, curMs, id, 3);
          else {
            if (turn > 0)
              updateTurn(ctx, curMs, id, turn);
            else
              ctx.response().end("fail");
          }
        }
        catch (Exception e) {
          System.out.println("new user" + id);
          updateTurn(ctx, curMs, id, 3);
        }
      })
      .doOnError(err -> { //todo don't have turn:id key
        System.out.println("new user:" + id);
        updateTurn(ctx, curMs, id, 3);
      }).subscribe();
  }

  void updateTurn(RoutingContext ctx, long curMs, String id, int turn) {
    turn--;
    String randSession = rand(10);
    rxCommand.set("session:" + id, randSession).subscribe();
    rxCommand.set("turn:" + id, turn + ":" + curMs).subscribe();
    ctx.response().end(randSession);
  }

  public static String rand(int len) {
    Random random = new Random();
    StringBuilder buffer = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      int randomLimitedInt = random.nextInt(123 - 97) + 97; //[a:z]
      buffer.append((char) randomLimitedInt);
    }
    return buffer.toString();
  }
}