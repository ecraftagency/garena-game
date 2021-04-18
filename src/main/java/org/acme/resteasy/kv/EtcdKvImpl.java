package org.acme.resteasy.kv;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.ibm.etcd.api.DeleteRangeResponse;
import com.ibm.etcd.api.KeyValue;
import com.ibm.etcd.api.PutResponse;
import com.ibm.etcd.api.RangeResponse;
import com.ibm.etcd.client.EtcdClient;
import com.ibm.etcd.client.KvStoreClient;
import com.ibm.etcd.client.kv.KvClient;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;

import java.util.List;
import java.util.concurrent.Executors;

@SuppressWarnings("unused")
public class EtcdKvImpl implements AsyncKVService {
  KvStoreClient             etcdClient;
  KvClient                  etcdService;
  ListeningExecutorService  service;

  public EtcdKvImpl(String host) {
    etcdClient    = EtcdClient.forEndpoint(host, 2379).withPlainText().build();
    etcdService   = etcdClient.getKvClient();
    service       = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));
  }

  public void rem(String key) {
    ByteString skey = ByteString.copyFromUtf8(key);

    Futures.addCallback(etcdService.delete(skey).async(),
            new FutureCallback<DeleteRangeResponse>() {
              public void onSuccess(DeleteRangeResponse explosion) {

              }
              @SuppressWarnings("NullableProblems")
              public void onFailure(Throwable thrown) {

              }
            }
            , service);
  }

  public void put(String key, String value) {
    ByteString skey = ByteString.copyFromUtf8(key);
    ByteString sval = ByteString.copyFromUtf8(value);

    Futures.addCallback(etcdService.put(skey, sval).async(),
      new FutureCallback<PutResponse>() {
        public void onSuccess(PutResponse explosion) {

        }
        @SuppressWarnings("NullableProblems")
        public void onFailure(Throwable thrown) {

        }
      }
      , service);
  }

  public void get(String key, Handler<AsyncResult<String>> handler) {
    ByteString skey = ByteString.copyFromUtf8(key);
    Futures.addCallback(etcdService.get(skey).async(),
      new FutureCallback<RangeResponse>() {
        public void onSuccess(RangeResponse resp) {
          List<KeyValue> kvs = resp.getKvsList();
          if (kvs.size() == 1) {
            String val = kvs.get(0).getValue().toStringUtf8();
            handler.handle(Future.succeededFuture(val));
          }
          else {
            handler.handle(Future.failedFuture("key not exist"));
          }
        }
        @SuppressWarnings("NullableProblems")
        public void onFailure(Throwable thrown) {

        }
      }
      , service);
  }
}