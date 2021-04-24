package org.acme.resteasy;

import com.ibm.etcd.client.EtcdClient;
import com.ibm.etcd.client.KvStoreClient;
import com.zen.kv.AsyncKVService;
import com.zen.kv.impl.*;
import com.zen.model.GameData;
import com.zen.utils.Utils;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.web.Route;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import reactor.core.publisher.Mono;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.nio.ByteBuffer;
import java.util.Random;

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

  final int dailyTurn = 10;
  final int refreshInterval = 5*60*1000;
  final int rewardTurn = 5;

  AsyncKVService<GameData> gameService;
  AsyncKVService<GameData> cacheGame;
  AsyncKVService<GameData> persistentGame;

  KvStoreClient etcdClient;
  RedisClient   rClient;

  void serviceStartup(@Observes StartupEvent ev) {
    etcdClient      = EtcdClient.forEndpoint(etcdHost, 2379).withPlainText().build();
    //v2
    rClient = RedisClient.create(redisHost);
    StatefulRedisConnection<String, ByteBuffer> conn = rClient.connect(new ByteBufferCodec());

    persistentGame     = new EtcdKvSvc<>(etcdClient, GameData.parser());
    cacheGame          = new RedisKvSvc<>(conn, GameData.parser());
    gameService        = new CompositeKVSvc<>(cacheGame, persistentGame);
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

  @Route(methods = HttpMethod.GET, regex = ".*/fbshare/v2")
  public void fbshareV2(RoutingContext ctx) {
    String id     = ctx.request().getParam("id");
    String token  = ctx.request().getParam("token");
    verify(id, token).doOnSuccess(gameData -> {
      if (gameData != null)
        handle(id, "fbshare", ctx, gameData);
      else
        ctx.response().setStatusCode(401).end();
    }).subscribe();
  }

  public Mono<GameData.Builder> verify(String id, String token) {
    return Mono.create(sink -> gameService.get(id + ":game").doOnSuccess(gameData -> {
      if (gameData != null && token.equals(gameData.getToken())) {
        GameData.Builder dgBD = GameData.newBuilder(gameData);
        long curMs = System.currentTimeMillis();
        if (curMs - dgBD.getLastGame() > refreshInterval) { //todo new day
          dgBD.setTurn(dailyTurn);
          dgBD.setLastGame(curMs);
        }
        sink.success(dgBD);
      }
      else
        sink.success(null);
    }).subscribe());
  }

  public void handle(String id, String command, RoutingContext ctx, GameData.Builder gameData) {
    switch (command) {
      case "gameData":
        processGetData(id, ctx, gameData);
        return;
      case "newGame":
        processNewGame(id, ctx, gameData);
        return;
      case "fbshare":
        processFBShare(id, ctx, gameData);
        return;
      default:
        ctx.response().setStatusCode(401).end();
    }
  }

  public void processFBShare(String id, RoutingContext ctx, GameData.Builder gameData) {
    long curMs = System.currentTimeMillis();
    if (Utils.dayDiff(gameData.getLastShare(), curMs) != 0) {
      int curTurn = gameData.getTurn();
      gameData.setTurn(curTurn + rewardTurn).setLastShare(curMs);
      gameService.put(id + ":game", gameData.build());
    }
    ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(gameData.build()));
  }

  public void processGetData(String id, RoutingContext ctx, GameData.Builder gameData) {
    GameData adjust = gameData.build();
    gameService.put(id + ":game", adjust);
    ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(adjust));
  }

  public void processNewGame(String id, RoutingContext ctx, GameData.Builder gameData) {
    long curMs    = System.currentTimeMillis();
    long lastGame = gameData.getLastGame();
    int turn      = gameData.getTurn();

    if (turn > 0) {
      String session = rand(10);
      gameData.setTurn(turn - 1);
      GameData adjust = gameData.setLastGame(curMs).setSession(session).build();

      gameService.put(id + ":game", adjust);
      ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(adjust));
    }
    else {
      GameData adjust = gameData.setSession("").build();

      gameService.put(id + ":game", adjust);
      ctx.response().putHeader("Content-Type", "text/json").end(AsyncKVService.json(adjust));
    }
  }
}