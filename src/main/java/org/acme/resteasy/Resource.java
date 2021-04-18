package org.acme.resteasy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.acme.resteasy.kv.AsyncKVService;
import org.acme.resteasy.kv.EtcdKvImpl;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/*
an ugly game logic handler
no repository,abstraction,dto,entity,model blah blah blah
 */
@ApplicationScoped
@SuppressWarnings("unused")
public class Resource {
  @ConfigProperty(name = "redis.ldb-name")
  String ldbName;
  @ConfigProperty(name = "etcd.hosts")
  String etcdHost;

  final int dailyTurn = 3;

  @ConfigProperty(name = "quarkus.redis.hosts")
  String                                redisConn;
  RedisClient                           redisClient;
  RedisReactiveCommands<String, String> rxCommand;
  AsyncKVService                        kvService;

  //todo use cache to mitigate etcd read operation
  //todo key value pair must be tiny so the cache remain under 100MB with 100000 index
  Map<String, String>   cache;
  String                lastCache     = "";
  int                   maxCacheSize  = 1000000;

  void serviceStartup(@Observes StartupEvent ev) {
    redisClient = RedisClient.create(redisConn);
    StatefulRedisConnection<String, String> connection = redisClient.connect();
    rxCommand = connection.reactive();
    kvService = new EtcdKvImpl(etcdHost);
    cache = new ConcurrentHashMap<>();
  }

  @Route(methods = HttpMethod.GET, regex = ".*/newgame")
  void newGame(RoutingContext ctx) {
    String id     = ctx.request().getParam("id");
    String token  = ctx.request().getParam("token");

    String dbToken = cache.get(id);
    //todo cacheMiss
    if (dbToken == null) {
      kvService.get(id, ar -> {
        if (ar.succeeded() && ar.result() != null && ar.result().equals(token)) {
          processNewGame(ctx, id);
          cache.put(id, ar.result());
          if (cache.size() > maxCacheSize)
            cache.remove(lastCache);
          lastCache = ar.result();
        }
        else {
          ctx.response().setStatusCode(404).end();
        }
      });
    }
    else {
      if (dbToken.equals(token))
        processNewGame(ctx, id);
      else
        ctx.response().setStatusCode(404).end();
    }
  }

  void processNewGame(RoutingContext ctx, String id) {
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
                  updateTurn(ctx, curMs, id, dailyTurn);
                else {
                  if (turn > 0)
                    updateTurn(ctx, curMs, id, turn);
                  else
                    ctx.response().end("oom");
                }
              }
              catch (Exception e) {
                updateTurn(ctx, curMs, id, dailyTurn);
              }
            })
            .doOnError(err -> { //todo don't have turn:id key
              updateTurn(ctx, curMs, id, dailyTurn);
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