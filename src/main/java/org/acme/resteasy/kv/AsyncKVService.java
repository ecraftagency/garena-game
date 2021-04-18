package org.acme.resteasy.kv;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;

//todo a simple kv<str> service
//first impl will be etcd, look ahead for rockDB implementation :D
@SuppressWarnings("unused")
public interface AsyncKVService {
  void get(String key, Handler<AsyncResult<String>> handler);
  void put(String key, String value);
  void rem(String key);
}