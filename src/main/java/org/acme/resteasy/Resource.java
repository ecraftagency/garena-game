package org.acme.resteasy;

import com.ibm.etcd.client.EtcdClient;
import com.ibm.etcd.client.KvStoreClient;
import com.zen.kv.AsyncKVService;
import com.zen.kv.impl.*;
import com.zen.model.GameData;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import reactor.core.publisher.Mono;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.nio.ByteBuffer;
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
  @ConfigProperty(name = "quarkus.redis.hosts")
  String redisHost;

  final int dailyTurn = 3;


  RedisClient                           redisClient;
  RedisReactiveCommands<String, String> rxCommand;
  AsyncKVService<String>                kvService;

  //todo use cache to mitigate etcd read operation
  //todo key value pair must be tiny so the cache remain under 100MB with 100000 index
  Map<String, String>   cache;
  String                lastCache     = "";
  int                   maxCacheSize  = 1000000;

  AsyncKVService<GameData> gameService;
  AsyncKVService<GameData> cacheGame;
  AsyncKVService<GameData> persistentGame;

  KvStoreClient etcdClient;
  RedisClient   rClient;

  void serviceStartup(@Observes StartupEvent ev) {
    redisClient     = RedisClient.create(redisHost);
    etcdClient      = EtcdClient.forEndpoint(etcdHost, 2379).withPlainText().build();

    StatefulRedisConnection<String, String> connection = redisClient.connect();
    rxCommand = connection.reactive();
    kvService = new SimpleKVService(etcdClient);
    cache = new ConcurrentHashMap<>();


    //v2
    rClient = RedisClient.create(redisHost);
    StatefulRedisConnection<String, ByteBuffer> conn = rClient.connect(new ByteBufferCodec());

    persistentGame     = new EtcdKvSvc<>(etcdClient, GameData.parser());
    cacheGame          = new RedisKvSvc<>(conn, GameData.parser());
    gameService        = new CompositeKVSvc<>(cacheGame, persistentGame);
  }

  @Route(methods = HttpMethod.GET, regex = ".*/newgame")
  void newGame(RoutingContext ctx) {
    String id       = ctx.request().getParam("id");
    String token    = ctx.request().getParam("token");
    String dbToken  = cache.get(id);
    //todo cache hit
    if (dbToken != null && dbToken.equals(token)) {
      processNewGame(ctx, id);
    }
    else {
      kvService.get(id)
        .doOnSuccess(resp -> {
          if (resp.equals(token)) {
            processNewGame(ctx, id);
            cache.put(id, resp);
            if (cache.size() > maxCacheSize)
              cache.remove(lastCache);
            lastCache = resp;
          }
        })
        .doOnError(err -> ctx.response().setStatusCode(401).end()).subscribe();
    }
  }

  @Route(methods = HttpMethod.GET, regex = ".*/data")
  void data(RoutingContext ctx) {
    String id       = ctx.request().getParam("id");
    String token    = ctx.request().getParam("token");
    String dbToken  = cache.get(id);

    if (dbToken != null && dbToken.equals(token)) {
      processGetUserInfo(ctx, id);
    }
    else {
      kvService.get(id)
        .doOnSuccess(resp -> {
          if (resp.equals(token)) {
            processGetUserInfo(ctx, id);
            cache.put(id, resp);
            if (cache.size() > maxCacheSize)
              cache.remove(lastCache);
            lastCache = resp;
          }
          else {
            ctx.response().setStatusCode(401).end();
          }
        })
        .doOnError(err -> ctx.response().setStatusCode(401).end()).subscribe();
    }
  }

  void processGetUserInfo(RoutingContext ctx, String id) {
    long curMs    = System.currentTimeMillis();
    String def    = 3 + ":" + curMs + ":" + 0;

    rxCommand.get("turn:" + id)
            .doOnSuccess(s -> {
              try {
                String[] turnInfo = s.split(":");
                if (turnInfo.length != 3)
                  throw new Exception("corrupt data");

                int turn          = Integer.parseInt(turnInfo[0]);
                long lastRequest  = Long.parseLong(turnInfo[1]);
                long score        = Long.parseLong(turnInfo[2]);
                if (curMs - lastRequest > 60*1000) {
                  turn = 3;
                  lastRequest = curMs;
                }
                String resp = turn + ":" + lastRequest + ":" + score;
                rxCommand.set("turn:" + id, resp).subscribe();
                ctx.response().end(resp);
              }
              catch (Exception e) {
                rxCommand.set("turn:" + id, def).subscribe();
                ctx.response().end(def);
              }
            })
            .doOnError(err -> {
              rxCommand.set("turn:" + id, def).subscribe();
              ctx.response().end(def);
            }).subscribe();
  }

  void processNewGame(RoutingContext ctx, String id) {
    long curMs = System.currentTimeMillis();
    rxCommand.get("turn:" + id)
            .doOnSuccess(s -> {
              try {
                String[] turnInfo = s.split(":");
                if (turnInfo.length != 3)
                  throw new Exception("corrupt data");

                int turn          = Integer.parseInt(turnInfo[0]);
                long lastRequest  = Long.parseLong(turnInfo[1]);
                long score        = Long.parseLong(turnInfo[2]);
                if (curMs - lastRequest > 60*1000)
                  updateTurn(ctx, curMs, id, dailyTurn, score);
                else {
                  if (turn > 0)
                    updateTurn(ctx, curMs, id, turn, score);
                  else
                    ctx.response().end("oom");
                }
              }
              catch (Exception e) {
                updateTurn(ctx, curMs, id, dailyTurn, 0);
              }
            })
            .doOnError(err -> { //todo don't have turn:id key
              updateTurn(ctx, curMs, id, dailyTurn, 0);
            }).subscribe();
  }

  void updateTurn(RoutingContext ctx, long curMs, String id, int turn, long score) {
    turn--;
    String randSession = rand(10);
    rxCommand.set("session:" + id, randSession).subscribe();
    rxCommand.set("turn:" + id, turn + ":" + curMs + ":" + score).subscribe();
    ctx.response().end(randSession + ":" + turn);
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


  ////////V2//////

  @Route(methods = HttpMethod.GET, regex = ".*/data/v2")
  public void gameData(RoutingContext ctx) {
    String id     = ctx.request().getParam("id");
    String token  = ctx.request().getParam("token");
    verify(id, token).doOnSuccess(gameData -> {
      if (gameData != null)
        handle(id, "gameData", ctx, gameData);
      else
        ctx.response().setStatusCode(401).end();
    }).subscribe();
  }

  @Route(methods = HttpMethod.GET, regex = ".*/newgame/v2")
  public void newGameV2(RoutingContext ctx) {
    String id     = ctx.request().getParam("id");
    String token  = ctx.request().getParam("token");
    verify(id, token).doOnSuccess(gameData -> {
      if (gameData != null)
        handle(id, "newGame", ctx, gameData);
      else
        ctx.response().setStatusCode(401).end();
    }).subscribe();
  }

  public Mono<GameData> verify(String id, String token) {
    return Mono.create(sink -> gameService.get(id + ":game").doOnSuccess(gameData -> {
      if (gameData != null && token.equals(gameData.getToken())) {
        sink.success(gameData);
      }
      else
        sink.success(null);
    }).subscribe());
  }

  public void handle(String id, String command, RoutingContext ctx, GameData gameData) {
    switch (command) {
      case "gameData":
        ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(gameData));
        return;
      case "newGame":
        processNewGame(id, ctx, gameData);
        return;
      default:
        ctx.response().setStatusCode(401).end();
    }
  }

  public void processNewGame(String id, RoutingContext ctx, GameData gameData) {
    long curMs    = System.currentTimeMillis();
    long lastGame = gameData.getLastGame();
    int turn      = gameData.getTurn();

    if (curMs - lastGame > 60*1000)
      turn = 3;
    turn--;

    if (turn >= 0) {
      String session = rand(10);
      GameData adjust = GameData.newBuilder(gameData).setTurn(turn).setLastGame(curMs).setSession(session).build();

      gameService.put(id + ":game", adjust);
      ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(adjust));
    }
    else {
      GameData adjust = GameData.newBuilder(gameData).setTurn(turn).setSession("").build();

      gameService.put(id + ":game", adjust);
      ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(adjust));
    }
  }
}