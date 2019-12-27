[TOC]

### 一. 背景

​	在开发中我们常会有这种需求：进入一个界面，请求一个接口，然后将接口的数据更新至UI上显示。在Android中，为了避免阻塞主线程，网络请求这种耗时操作需要放在子线程中，但在Android中，UI控件不是线程安全的，系统不允许在子线程中去更新UI。

​	因此现在的情况是，网络请求需要在子线程中，但是UI的更新需要放在主线程中，那么要如何在子线程获取接口数据后，通知主线更新UI呢？由此引出本文的主角 **Handler**。

### 二. 介绍

Handler主要用于线程之前的通信问题。

以背景中的问题为例子，界面中在子线程中请求网络数据，然后在子线程中更新UI。

首先在主线程中创建一个Handler，并且执行收到消息的操作

```java
 private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(@NonNull Message msg) {
            //收到消息后的操作

            switch (msg.what) {
                case MSG_UPDATE_TV:
                    mTv.setText(msg.obj.toString());
                    break;
                default:
                    break;
            }
        }
    };
```

开启一个子线程模拟网络耗时操作，获取数据后通过Handler将消息发送至主线程

```java
new Thread(new Runnable() {
  @Override
  public void run() {
    // 模拟网络请求
    SystemClock.sleep(3000);

    Message message = Message.obtain();
    message.what = MSG_UPDATE_TV;
    message.obj = "接口数据";
    mHandler.sendMessage(message);
  }
}).start();
```

看到这里想必有同学心中就有个疑问了，为什么通过Handler可以将数据从子线程中发送至主线程中？

### 三. 源码分析

要分析Handler的原理，就要介绍一下与它息息相关的几个类

|      类      |                  职责                  |
| :----------: | :------------------------------------: |
|   Handler    |           用于发送与处理消息           |
|   Message    |          线程间通信消息的载体          |
| MessageQueue |                消息队列                |
|    Looper    | 用于不断的从队列中取消息，然后进行分发 |
| ThreadLocal  |        存储线程作用域的局部变量        |

#### 3.1 Message 

通过Handler实现线程间的数据传输，首先要依靠Message，顾名思义，也就是消息的载体。

```java
Message message = Message.obtain();
message.what = MSG_UPDATE_TV;
message.obj = "接口数据";
```

Message用于携带线程间通信的一些数据

```java
public final class Message implements Parcelable {
    
    // 一般用于标识消息类型
    public int what;

    // 传递int类型数据
    public int arg1;

    // 传递int类型数据
    public int arg2;

    // 传递普通对象，
    public Object obj;

    // Bundle数据
    Bundle data;

    // 保存发送当前消息的Handler，也是处理该消息的Handler
    Handler target;
    ...
}
```

关于Message对象的创建，不建议使用new直接创建Message对象，系统存在Message的消息池，可实现消息对象的复用，避免大量创建消息对象带来的开销问题。

- 消息以链表的方式组成消息池
- 消息池存在消息的最大缓存个数，最大消息个数为50个
- 每次从消息池中获取消息时，若消息池中不存在消息，则新建消息，否则取出消息链表表头
- 回收消息时，若当前消息池中消息缓存个数小于最大消息数，则将消息加入消息池中

```java
public final class Message implements Parcelable {
    //消息池以链表的形式存储
    Message next;

    public static final Object sPoolSync = new Object();
    private static Message sPool;
    //当前消息池内存在的消息数
    private static int sPoolSize = 0;

    //消息池中最大的消息数
    private static final int MAX_POOL_SIZE = 50;

    //获取消息
    public static Message obtain() {
        synchronized (sPoolSync) {
            if (sPool != null) {
                //消息池中存在消息，取出消息头
                Message m = sPool;
                sPool = m.next;
                m.next = null;
                m.flags = 0; // clear in-use flag
                sPoolSize--;
                return m;
            }
        }
        return new Message();
    }

    // 消息回收
    void recycleUnchecked() {
        ... 
        synchronized (sPoolSync) {
            if (sPoolSize < MAX_POOL_SIZE) {
                //回收消息时,若当前消息池内数目小于最大数，则将消息放入消息池
                next = sPool;
                sPool = this;
                sPoolSize++;
            }
        }
    }
}
```

#### 3.2 Handler

Handler的Api可以分成三类：发送消息、处理消息、移除消息

- 发送消息

  发送的数据可以是Runnable或者其他的数据类型，无论发送什么数据，最终数据会被封装成Messages对象

  调用enqueueMessage将消息加入到消息队列中。

- 处理消息

  消息最终由发送该Message的Handler进行处理(待会会分析)，消息的处理调用Handler的dispatchMessage方法，该方法中执行了处理消息的优先级，优先级从高到底为使用postXX发送的Runnable消息 > 在Handler构造方法传入的Callback消息体 > 使用sendXXMessage发送的消息

- 移除消息

  通过removeXX来移除消息队列中未处理的Message，注意只能是还未进行处理的消息

```java
public class Handler {


    public Handler(@Nullable Callback callback, boolean async) {
        if (FIND_POTENTIAL_LEAKS) {
            final Class<? extends Handler> klass = getClass();
            if ((klass.isAnonymousClass() || klass.isMemberClass() || klass.isLocalClass()) &&
                    (klass.getModifiers() & Modifier.STATIC) == 0) {
                Log.w(TAG, "The following Handler class should be static or leaks might occur: " +
                    klass.getCanonicalName());
            }
        }

        mLooper = Looper.myLooper();
        // 若创建Handler时looper不存在，则会发生崩溃，这也是在子线程中不能直接创建Hander的原因
        if (mLooper == null) {
            throw new RuntimeException(
                "Can't create handler inside thread " + Thread.currentThread()
                        + " that has not called Looper.prepare()");
        }
        mQueue = mLooper.mQueue;
        mCallback = callback;
        mAsynchronous = async;
    }


    //被子类重写，接收消息时做 一些操作
    public void handleMessage(Message msg) {
    }   

    //-----------发送消息
    public final boolean sendMessage(@NonNull Message msg)
    public final boolean sendEmptyMessage(int what)
    public final boolean sendEmptyMessageDelayed(int what, long delayMillis)
    public final boolean sendEmptyMessageAtTime(int what, long uptimeMillis)
    public final boolean sendMessageDelayed(@NonNull Message msg, long delayMillis)
    public boolean sendMessageAtTime(@NonNull Message msg, long uptimeMillis)
    public final boolean sendMessageAtFrontOfQueue(@NonNull Message msg)
    public final boolean post(@NonNull Runnable r)
    public final boolean postAtTime(@NonNull Runnable r, long uptimeMillis)
    public final boolean postAtTime(@NonNull Runnable r, @Nullable Object token, long uptimeMillis)
    public final boolean postDelayed(@NonNull Runnable r, long delayMillis)
    public final boolean postDelayed(Runnable r, int what, long delayMillis)
    public final boolean postDelayed(@NonNull Runnable r, @Nullable Object token, long delayMillis)
    public final boolean postAtFrontOfQueue(@NonNull Runnable r)
    //-----------发送消息
    
    // 消息入队,上述所有发送消息的方法都会调用该方法
    private boolean enqueueMessage(@NonNull MessageQueue queue, @NonNull Message msg,
            long uptimeMillis) {
        // 设置消息的处理者
        msg.target = this;
        msg.workSourceUid = ThreadLocalWorkSource.getUid();

        if (mAsynchronous) {
            msg.setAsynchronous(true);
        }
        //将消息加入队列
        return queue.enqueueMessage(msg, uptimeMillis);
    }

    /** 处理消息
     *  消息处理存在优先级，处理顺序 通过postXXX发送的消息>直接在Handler构造方法传入的消息>sendMessage发送的消息
     **/
    public void dispatchMessage(@NonNull Message msg) {
        if (msg.callback != null) {
            handleCallback(msg);
        } else {
            if (mCallback != null) {
                if (mCallback.handleMessage(msg)) {
                    return;
                }
            }
            handleMessage(msg);
        }
    }

    // 移除消息队列中what的待处理消息
    public final void removeMessages(int what)
    // 移除消息队列中所有what和object字段为object的待处理消息，obj为null,则移除所有what的待处理消息
    public final void removeMessages(int what, @Nullable Object object)
    // 移除消息队列中obj为token的待处理消息，回调，若token为null，则移除所有所有消息和回调
    public final void removeCallbacksAndMessages(@Nullable Object token)

} 
```

#### 3.3 MessageQueue

消息队列中的Message其实是以链表的方式按消息执行的时间点从小到大存储的，表头的执行时间点最小，也就是最先处理

消息入队：enqueueMessage

- 若队列中不存在消息，或者新入队的消息执行时间点比表头小，则要创建消息表头
- 存在表头的情况下，将消息插入到有序链表中

```java
boolean enqueueMessage(Message msg, long when) {

  if (msg.target == null) {
    throw new IllegalArgumentException("Message must have a target.");
  }
  if (msg.isInUse()) {
    throw new IllegalStateException(msg + " This message is already in use.");
  }

  synchronized (this) {
    if (mQuitting) {
      IllegalStateException e = new IllegalStateException(
        msg.target + " sending message to a Handler on a dead thread");
      Log.w(TAG, e.getMessage(), e);
      msg.recycle();
      return false;
    }

    // 标记Message在使用中
    msg.markInUse();
    msg.when = when;
    // 消息队列是一个链表，这里使得p指向表头
    Message p = mMessages;
    // 是否需要唤醒
    boolean needWake;
    if (p == null || when == 0 || when < p.when) {
      // 消息队列中不存在消息的情况下或者消息执行时间点比表头小，创建表头
      msg.next = p;
      mMessages = msg;
      // 唤醒阻塞
      needWake = mBlocked;
    } else {
      // 如果是处于阻塞状态且消息为异步消息，消息头为屏障，则需要唤醒阻塞
      needWake = mBlocked && p.target == null && msg.isAsynchronous();
      Message prev;
      //链表是根据消息执行时间点从小到大排列的
      for (;;) {
        prev = p;
        p = p.next;
        if (p == null || when < p.when) {
          break;
        }
        // 若异步消息未到达执行时间点，不需要唤醒
        if (needWake && p.isAsynchronous()) {
          needWake = false;
        }
      }
      msg.next = p; // invariant: p == prev.next
      prev.next = msg;
    }

    // 唤醒阻塞,nativePollOnce方法阻塞解除
    if (needWake) {
      nativeWake(mPtr);
    }
  }
  return true;
}
```

消息出队

- 若消息队列中没有消息或者消息未到达执行时间，则消息队列会处于阻塞状态
- 若消息队列中存在可以执行的消息，则将消息从链表中移除，然后返回该消息

```java
// 取出消息队列的消息
Message next() {
  final long ptr = mPtr;
  if (ptr == 0) {
    return null;
  }

  int pendingIdleHandlerCount = -1; // -1 only during first iteration
  int nextPollTimeoutMillis = 0;

  for (;;) {
    if (nextPollTimeoutMillis != 0) {
      Binder.flushPendingCommands();
    }

    // 当有消息入队或者消息执行时间达到，消息队列就会被唤醒，否则会一直阻塞
    nativePollOnce(ptr, nextPollTimeoutMillis);

    synchronized (this) {
      final long now = SystemClock.uptimeMillis();
      Message prevMsg = null;
      Message msg = mMessages;
      if (msg != null && msg.target == null) {
        // 若消息头是一个消息屏障，找到消息队列中第一个异步消息
        do {
          prevMsg = msg;
          msg = msg.next;
        } while (msg != null && !msg.isAsynchronous());
      }
      if (msg != null) {
        if (now < msg.when) {
          // 同步消息或者异步消息为达到消息的执行时间，设置下次唤醒的超时时间
          nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
        } else {
          // 消息已到达执行时间
          mBlocked = false;
          // 把消息从消息队列中移除
          if (prevMsg != null) {
            prevMsg.next = msg.next;
          } else {
            mMessages = msg.next;
          }
          msg.next = null;
          msg.markInUse();
          //返回消息
          return msg;
        }
      } else {
        // 没有消息
        nextPollTimeoutMillis = -1;
      }

      // Process the quit message now that all pending messages have been handled.
      if (mQuitting) {
        // 消息队列被停止，返回null
        dispose();
        return null;
      }


      if (pendingIdleHandlerCount < 0
          && (mMessages == null || now < mMessages.when)) {
        pendingIdleHandlerCount = mIdleHandlers.size();
      }
      if (pendingIdleHandlerCount <= 0) {
        // 没有可以立即处理的消息，继续阻塞
        mBlocked = true;
        continue;
      }
      ...
    }
  }
```

#### 3.4 ThreadLocal

ThreadLocal简单的讲是一个用于存储线程作用域局部变量的类

这边用一个简单的例子来说明

- 创建一个ThreadLocal成员属性，在主线程使用set设置值，然后在子线程中通过get方法去取出值
- 在子线程中通过set方法去设置值，然后在主线程中调用get方法去获取值

```java
ThreadLocal<String> threadLocal = new ThreadLocal<>();
private void threadLocal() {
  threadLocal.set("这边是主线程的数据");

  new Thread(new Runnable() {
    @Override
    public void run() {
      String msg = threadLocal.get();
      Log.e(TAG, "threadLocal " + Thread.currentThread() + " msg = " + msg);
      threadLocal.set("子线程中的数据");
    }
  }).start();


  mHandler.post(new Runnable() {
    @Override
    public void run() {
      String msg = threadLocal.get();
      Log.e(TAG, "threadLocal " + Thread.currentThread() + " msg = " + msg);
    }
  });
}
```

执行结果

> E/Handler==>>: threadLocal Thread[Thread-3,5,main] msg = null
>
> E/Handler==>>: threadLocal Thread[main,5,main] msg = 这边是主线程的数据

可以看到在主线程通过set方法在ThreadLocal设置值后，在子线程中取出的值确是null；同样在子线程中通过set方法给ThreadLocal设置值，在主线程中取出，发现取出的值并没有发生改变，这就是ThreadLocal变量的线程作用域，也就是说存储的变量只在当前线程内有效，该变量对其他线程来说是不可见的。

我们来分析一下它的实现原理，首先通过set方法来设置变量

- 存在一个线程与变量的map，存储变量时，首先根据线程获取对应的ThreadLocalMap，若存在则更新map的值
- 若ThreadLocalMap不存在，则创建对应ThreadLocalMap，把值放入Map中

```java
public void set(T value) {
  Thread t = Thread.currentThread();
  ThreadLocalMap map = getMap(t);
  if (map != null)
    map.set(this, value);
  else
    createMap(t, value);
}
```

我们来看看getMap和createMap这两个方法，不难发现两个方法其实都是去操作了Thread的一个成员属性，也就是说无论在哪个线程中去调用ThreadLocalMap的set方法，最终还是将值存储在了对应线程的ThreadMap中，不同的线程有着不同的ThreadLocalMap实例。

```java
ThreadLocalMap getMap(Thread t) {
  return t.threadLocals;
}

void createMap(Thread t, T firstValue) {
  t.threadLocals = new ThreadLocalMap(this, firstValue);
}
```

同理，看下ThreadLocal的get方法

- 拿到调用当前方法的线程信息
- 从当前线程的成员属性ThreadLocalMap取出属性值，不存在则在Map中插入默认值，并且返回

```java
public T get() {
  Thread t = Thread.currentThread();
  ThreadLocalMap map = getMap(t);
  if (map != null) {
    ThreadLocalMap.Entry e = map.getEntry(this);
    if (e != null) {
      @SuppressWarnings("unchecked")
      T result = (T)e.value;
      return result;
    }
  }
  return setInitialValue();
}
```

#### 3.6 Looper

Looper的职责是不断的从消息队列中取出消息，然后将消息进行分发。

- Looper在创建时会自动关联消息队列以及Looper对应的线程
- loop方法中是一个死循环，会不断的从消息队列中取消息，有消息则将消息分发，最终消息的处理还是交给发送该消息的Handler
- 若消息队列中不存在可以立刻执行的消息，则会一直处于阻塞状态(next方法)，直到有可执行的消息
- Looper有两种停止方式：强制停止与安全停止。强制停止会移除消息队列中所有的消息，而安全的停止只会移除消息队列中未到执行时间的消息，达到执行时间或者正在执行的消息还是会继续处理，直到队列中不存在消息，此时停止消息队列，再停止Looper

```java
public final class Looper {
    // 线程-Looper Map
    static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<Looper>();

    // 主线程对应的Looper
    private static Looper sMainLooper; 

    final MessageQueue mQueue;

    // 创建Looper的线程
    final Thread mThread;
    
    // quitAllowed表示是否允许停止
    private Looper(boolean quitAllowed) {
        // 创建Looper时会创建消息队列，并且与创建Looper的线程关联
        mQueue = new MessageQueue(quitAllowed);
        mThread = Thread.currentThread();
    }

    // 创建当前线程的Looper
    public static void prepare()
    private static void prepare(boolean quitAllowed){
        if (sThreadLocal.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        sThreadLocal.set(new Looper(quitAllowed));
    }

    // 创建主线程对应的Looper
    public static void prepareMainLooper()

    public static void loop() {
        final Looper me = myLooper();
        if (me == null) {
            throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
        }
        final MessageQueue queue = me.mQueue;
        ...

        // 死循环
        for (;;) {
            // 若消息队列中没有可执行的消息，则会处于阻塞状态
            Message msg = queue.next(); 
            if (msg == null) {
                // 消息队列停止，msg返回null
                return;
            }

            ... 

            long origWorkSource = ThreadLocalWorkSource.setUid(msg.workSourceUid);
            try {
                //将取出的下次进行分发，target为发送该消息的Handler
                msg.target.dispatchMessage(msg);
                if (observer != null) {
                    observer.messageDispatched(token, msg);
                }
                dispatchEnd = needEndTime ? SystemClock.uptimeMillis() : 0;
            } catch (Exception exception) {
                if (observer != null) {
                    observer.dispatchingThrewException(token, msg, exception);
                }
                throw exception;
            } finally {
                ThreadLocalWorkSource.restore(origWorkSource);
                if (traceTag != 0) {
                    Trace.traceEnd(traceTag);
                }
            }
            
            ...
            msg.recycleUnchecked();
        }
    }

    //直接停止，会移除队列中所有的消息，包括正在使用的消息
    public void quit() {
        mQueue.quit(false);
    }

    // 安全地停止，只未到执行时间点的消息，而达到执行时间点的消息或者正在执行的消息还会执行完
    public void quitSafely() {
        mQueue.quit(true);
    }
}
```

这边看下Looper的停止，可以看到，停止Looper，最终还是要先停止消息队列

MessageQueue.java

```java
void quit(boolean safe) {
  // 若消息队列不能被停止，此时会抛出异常
  if (!mQuitAllowed) {
    throw new IllegalStateException("Main thread not allowed to quit.");
  }

  synchronized (this) {
    if (mQuitting) {
      // 若已设置停止标志位，说明正在停止或者已经停止，直接返回
      return;
    }
    mQuitting = true;

    if (safe) {
      // 安全的停止
      removeAllFutureMessagesLocked();
    } else {
      // 强制停止
      removeAllMessagesLocked();
    }

    // We can assume mPtr != 0 because mQuitting was previously false.
    nativeWake(mPtr);
  }
}
```

首先看安全的停止

- 若表头执行时间点未到，整个消息队列中的消息都回收
- 消息队列中正在执行的消息或者已经到达执行时间点的消息还会继续执行

```java
private void removeAllFutureMessagesLocked() {
  final long now = SystemClock.uptimeMillis();
  // 一个按执行时间点从小到大排列的链表
  Message p = mMessages;
  if (p != null) {
    if (p.when > now) {
      // 表头的执行时间还未到，说明整个链表都是执行时间未到的消息，全部取消
      removeAllMessagesLocked();
    } else {
      // 表头为立刻执行的消息
      Message n;
      // 找到第一个未到执行时间点的消息
      for (;;) {
        n = p.next;
        if (n == null) {
          return;
        }
        if (n.when > now) {
          break;
        }
        p = n;
      }
      p.next = null;
      //将所有未到执行时间点的消息全部移除回收
      do {
        p = n;
        n = p.next;
        p.recycleUnchecked();
      } while (n != null);
    }
  }
}
```

从上可知，在安全停止时，消息队列中所有的消息还为达到执行时间，则会调用强制取消的方法

```java
private void removeAllMessagesLocked() {
  Message p = mMessages;
  //遍历整个链表，回收所有的消息
  while (p != null) {
    Message n = p.next;
    p.recycleUnchecked();
    p = n;
  }
  mMessages = null;
}
```

#### 3.7 总结

![handler](https://i.postimg.cc/0NpmJzrF/handler.png)

- Handler将消息发送至消息队列，消息在消息队列中按照执行的时间点从小到大且以链表的方式排列
- Looper用于从消息队列中取出消息和对消息进行分发，loop是一个死循环，它会不断的从消息队列中取出消息，若存在可执行的消息，则将消息分发至Handler进行处理
- 若消息队列中不存在可以立即执行的消息，则Looper会处于阻塞状态，直到消息队列中存在可立即执行的消息

### 四. 常见问题

#### 4.1 子线程是否能创建Handler

可以，但是需要为子线程创建Looper，若子线程中不存在对应的Looper，则会抛出异常

```java
public Handler(@Nullable Callback callback, boolean async) {
  mLooper = Looper.myLooper();
  if (mLooper == null) {
    throw new RuntimeException(
      "Can't create handler inside thread " + Thread.currentThread()
      + " that has not called Looper.prepare()");
  }
}
```

此时有人可能有疑问，为什么在主线程创建Handler不需要我们手动创建Looper。其实这个创建Looper的过程Android的Framework已经帮我们实现了。

ActivityThread.java

```java
public static void main(String[] args) {
 	...
  
  // 为主线程创建Looper
  Looper.prepareMainLooper();
  
  ActivityThread thread = new ActivityThread();
  thread.attach(false, startSeq);

  if (sMainThreadHandler == null) {
    sMainThreadHandler = thread.getHandler();
  }
 	// 启动looper
  Looper.loop();

  throw new RuntimeException("Main thread loop unexpectedly exited");
}
```

#### 4.2 在同一线程中 android.Handler 和 android.MessaegQueue 的数量对应关系 

一个线程只存在一个MessageQueue和Looper，但可以有多个Handler，所以是多对一。

例如Activity的onCreate、onResume()等方法都是通过底层发送的消息而回调的

参考资料：

- 《Android开发艺术探索》

-   [Handler机制——同步屏障](https://blog.csdn.net/start_mao/article/details/98963744)

