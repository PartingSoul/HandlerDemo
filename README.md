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
        public void dispatchMessage(@NonNull Message msg) {
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

消息队列中的Message其实是以链表的方式按消息执行的延迟时间从小到大存储的，表头的延迟执行时间最小。

消息入队：enqueueMessage

- 若队列中不存在消息，或者新入队的消息延迟时间比表头小，则要创建消息表头
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
      // 消息队列中不存在消息的情况下或者消息延迟时间比表头小，创建表头
      msg.next = p;
      mMessages = msg;
      // 唤醒阻塞
      needWake = mBlocked;
    } else {
      // 如果是处于阻塞状态且消息为异步消息，消息头为屏障，则需要唤醒阻塞
      needWake = mBlocked && p.target == null && msg.isAsynchronous();
      Message prev;
      //链表是根据延迟时间从小到大排列的，表头的延迟时间最小
      for (;;) {
        prev = p;
        p = p.next;
        if (p == null || when < p.when) {
          break;
        }
        // 若异步消息未到达执行时间，不需要唤醒
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



