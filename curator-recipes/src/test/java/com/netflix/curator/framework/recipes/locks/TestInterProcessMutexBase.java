/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.curator.framework.recipes.locks;

import com.google.common.collect.Lists;
import com.google.common.io.Closeables;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.framework.recipes.BaseClassForTests;
import com.netflix.curator.retry.RetryOneTime;
import com.netflix.curator.test.KillSession;
import com.netflix.curator.test.Timing;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public abstract class TestInterProcessMutexBase extends BaseClassForTests
{
    private volatile CountDownLatch         waitLatchForBar = null;
    private volatile CountDownLatch         countLatchForBar = null;

    protected abstract InterProcessLock      makeLock(CuratorFramework client);

    @Test
    public void     testKilledSession() throws Exception
    {
        final Timing        timing = new Timing();

        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        client.start();
        try
        {
            final InterProcessLock mutex1 = makeLock(client);
            final InterProcessLock mutex2 = makeLock(client);

            final Semaphore semaphore = new Semaphore(0);
            ExecutorCompletionService<Object> service = new ExecutorCompletionService<Object>(Executors.newFixedThreadPool(2));
            service.submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        mutex1.acquire();
                        semaphore.release();
                        Thread.sleep(1000000);
                        return null;
                    }
                }
            );

            service.submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        mutex2.acquire();
                        semaphore.release();
                        Thread.sleep(1000000);
                        return null;
                    }
                }
            );

            Assert.assertTrue(timing.acquireSemaphore(semaphore, 1));
            KillSession.kill(client.getZookeeperClient().getZooKeeper(), server.getConnectString());
            Assert.assertTrue(timing.acquireSemaphore(semaphore, 1));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void     testWithNamespace() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.builder().
            connectString(server.getConnectString()).
            retryPolicy(new RetryOneTime(1)).
            namespace("test").
            build();
        client.start();
        try
        {
            InterProcessLock mutex = makeLock(client);
            mutex.acquire(10, TimeUnit.SECONDS);
            Thread.sleep(100);
            mutex.release();
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void     testReentrantSingleLock() throws Exception
    {
        final int           THREAD_QTY = 10;
        
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            final AtomicBoolean     hasLock = new AtomicBoolean(false);
            final AtomicBoolean     isFirst = new AtomicBoolean(true);
            final Semaphore         semaphore = new Semaphore(1);
            final InterProcessLock  mutex = makeLock(client);

            List<Future<Object>>    threads = Lists.newArrayList();
            ExecutorService         service = Executors.newCachedThreadPool();            
            for ( int i = 0; i < THREAD_QTY; ++i )
            {
                Future<Object>          t = service.submit
                (
                    new Callable<Object>()
                    {
                        @Override
                        public Object call() throws Exception
                        {
                            semaphore.acquire();
                            mutex.acquire();
                            Assert.assertTrue(hasLock.compareAndSet(false, true));
                            try
                            {
                                if ( isFirst.compareAndSet(true, false) )
                                {
                                    semaphore.release(THREAD_QTY - 1);
                                    while ( semaphore.availablePermits() > 0 )
                                    {
                                        Thread.sleep(100);
                                    }
                                }
                                else
                                {
                                    Thread.sleep(100);
                                }
                            }
                            finally
                            {
                                mutex.release();
                                hasLock.set(false);
                            }
                            return null;
                        }
                    }
                );
                threads.add(t);
            }

            for ( Future<Object> t : threads )
            {
                t.get();
            }
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void     testReentrant2Threads() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            waitLatchForBar = new CountDownLatch(1);
            countLatchForBar = new CountDownLatch(1);

            final InterProcessLock mutex = makeLock(client);
            Executors.newSingleThreadExecutor().submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        Assert.assertTrue(countLatchForBar.await(10, TimeUnit.SECONDS));
                        try
                        {
                            mutex.acquire(10, TimeUnit.SECONDS);
                            Assert.fail();
                        }
                        catch ( Exception e )
                        {
                            // correct
                        }
                        finally
                        {
                            waitLatchForBar.countDown();
                        }
                        return null;
                    }
                }
            );

            foo(mutex);
            Assert.assertFalse(mutex.isAcquiredInThisProcess());
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void     testReentrant() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            InterProcessLock mutex = makeLock(client);
            foo(mutex);
            Assert.assertFalse(mutex.isAcquiredInThisProcess());
        }
        finally
        {
            client.close();
        }
    }

    private void        foo(InterProcessLock mutex) throws Exception
    {
        mutex.acquire(10, TimeUnit.SECONDS);
        Assert.assertTrue(mutex.isAcquiredInThisProcess());
        bar(mutex);
        Assert.assertTrue(mutex.isAcquiredInThisProcess());
        mutex.release();
    }

    private void        bar(InterProcessLock mutex) throws Exception
    {
        mutex.acquire(10, TimeUnit.SECONDS);
        Assert.assertTrue(mutex.isAcquiredInThisProcess());
        if ( countLatchForBar != null )
        {
            countLatchForBar.countDown();
            waitLatchForBar.await(10, TimeUnit.SECONDS);
        }
        snafu(mutex);
        Assert.assertTrue(mutex.isAcquiredInThisProcess());
        mutex.release();
    }

    private void        snafu(InterProcessLock mutex) throws Exception
    {
        mutex.acquire(10, TimeUnit.SECONDS);
        Assert.assertTrue(mutex.isAcquiredInThisProcess());
        mutex.release();
        Assert.assertTrue(mutex.isAcquiredInThisProcess());
    }

    @Test
    public void     test2Clients() throws Exception
    {
        CuratorFramework client1 = null;
        CuratorFramework client2 = null;
        try
        {
            client1 = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
            client2 = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(1));
            client1.start();
            client2.start();

            final InterProcessLock mutexForClient1 = makeLock(client1);
            final InterProcessLock mutexForClient2 = makeLock(client2);

            final CountDownLatch              latchForClient1 = new CountDownLatch(1);
            final CountDownLatch              latchForClient2 = new CountDownLatch(1);
            final CountDownLatch              acquiredLatchForClient1 = new CountDownLatch(1);
            final CountDownLatch              acquiredLatchForClient2 = new CountDownLatch(1);

            final AtomicReference<Exception>  exceptionRef = new AtomicReference<Exception>();

            ExecutorService                   service = Executors.newCachedThreadPool();
            Future<Object>                    future1 = service.submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        try
                        {
                            mutexForClient1.acquire(10, TimeUnit.SECONDS);
                            acquiredLatchForClient1.countDown();
                            latchForClient1.await(10, TimeUnit.SECONDS);
                            mutexForClient1.release();
                        }
                        catch ( Exception e )
                        {
                            exceptionRef.set(e);
                        }
                        return null;
                    }
                }
            );
            Future<Object>                    future2 = service.submit
            (
                new Callable<Object>()
                {
                    @Override
                    public Object call() throws Exception
                    {
                        try
                        {
                            mutexForClient2.acquire(10, TimeUnit.SECONDS);
                            acquiredLatchForClient2.countDown();
                            latchForClient2.await(10, TimeUnit.SECONDS);
                            mutexForClient2.release();
                        }
                        catch ( Exception e )
                        {
                            exceptionRef.set(e);
                        }
                        return null;
                    }
                }
            );

            while ( !mutexForClient1.isAcquiredInThisProcess() && !mutexForClient2.isAcquiredInThisProcess() )
            {
                Thread.sleep(1000);
                Assert.assertFalse(future1.isDone() && future2.isDone());
            }

            Assert.assertTrue(mutexForClient1.isAcquiredInThisProcess() != mutexForClient2.isAcquiredInThisProcess());
            Thread.sleep(1000);
            Assert.assertTrue(mutexForClient1.isAcquiredInThisProcess() || mutexForClient2.isAcquiredInThisProcess());
            Assert.assertTrue(mutexForClient1.isAcquiredInThisProcess() != mutexForClient2.isAcquiredInThisProcess());

            Exception exception = exceptionRef.get();
            if ( exception != null )
            {
                throw exception;
            }

            if ( mutexForClient1.isAcquiredInThisProcess() )
            {
                latchForClient1.countDown();
                Assert.assertTrue(acquiredLatchForClient2.await(10, TimeUnit.SECONDS));
                Assert.assertTrue(mutexForClient2.isAcquiredInThisProcess());
            }
            else
            {
                latchForClient2.countDown();
                Assert.assertTrue(acquiredLatchForClient1.await(10, TimeUnit.SECONDS));
                Assert.assertTrue(mutexForClient1.isAcquiredInThisProcess());
            }
        }
        finally
        {
            Closeables.closeQuietly(client1);
            Closeables.closeQuietly(client2);
        }
    }
}