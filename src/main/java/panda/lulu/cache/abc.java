
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;

import cn.allinmd.base.framework.annotations.RedisLockable;
import cn.allinmd.base.framework.cache.redis.RedisClient;
import cn.allinmd.base.framework.cache.redis.RedisLockException;
import cn.allinmd.base.framework.spelKey.KeySpringELExpressionAdviceSupport;
import com.google.common.base.Joiner;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;


@Component
@Aspect
public class RedisLockInterceptor extends KeySpringELExpressionAdviceSupport {

    private static final String REDIS_LOCK_OK = "OK";
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisLockInterceptor.class);

    @Resource
    private RedisClient redisClient;

    @Around("@annotation(cn.allinmd.base.framework.annotations.RedisLockable)")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        Object target = joinPoint.getTarget();
        Class<?> targetClass = joinPoint.getTarget().getClass();
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        String methodName = methodSignature.getName();
        String targetClassName = targetClass.getName();
        Method targetMethod = AopUtils.getMostSpecificMethod(methodSignature.getMethod(), targetClass);

        RedisLockable redisLock = targetMethod.getAnnotation(RedisLockable.class);
        int expireSeconds = redisLock.expiration();
        String redisKey = getLockKey(redisLock, args, target, targetMethod, targetClassName, methodName);
        boolean getRedisLock = false;
        if (redisLock.isWaiting()) {
            getRedisLock = getWaitingLock(redisKey, expireSeconds, redisLock.retryCount());
        } else {
            getRedisLock = noWaitingLock(redisKey, expireSeconds);
        }

        if (getRedisLock) {
            long startTime = System.currentTimeMillis();
            try {
                return joinPoint.proceed();
            } finally {
                long lastedTime = System.currentTimeMillis() - startTime;
                if (lastedTime <= expireSeconds * 1000) {
                    unlock(redisKey);
                }
            }
        } else {
            throw new RedisLockException(redisKey);
        }
    }

    private void unlock(String redisKey) {
        redisClient.del(redisKey);
    }

    /**
     * 获取redis锁，
     *
     * @param redisKey      redisKey
     * @param expireSeconds 获取锁超时时间，单位为秒
     * @return 是否获取成功
     */
    private boolean noWaitingLock(String redisKey, int expireSeconds) {
        long expireTime = System.currentTimeMillis() + expireSeconds * 1000;
        String lockResult = redisClient.setnx(redisKey, expireTime, expireSeconds);
        if (REDIS_LOCK_OK.equalsIgnoreCase(lockResult)) {
            return true;
        }
        Long redisExpireTime = Long.parseLong(redisClient.get(redisKey, "0", false));
        if (redisExpireTime <= System.currentTimeMillis()) {
            long newExpireTime = System.currentTimeMillis() + expireSeconds * 1000;
            String oldRedisValue = redisClient.getSet(redisKey, String.valueOf(newExpireTime));
            if (StringUtils.isEmpty(oldRedisValue)) {
                return true;
            }
            long oldExpireTime = Long.parseLong(oldRedisValue);
            if (oldExpireTime == redisExpireTime) {
                return true;
            }
        }
        return false;
    }

    private boolean getWaitingLock(String redisKey, int expireSeconds, int retryCount) {
        int count = 0;
        while (retryCount == -1 || retryCount <= count) {
            if (noWaitingLock(redisKey, expireSeconds)) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                LOGGER.error("尝试获取redis锁失败,thread name:{}", Thread.currentThread().getName());
            }
            count++;
        }
        return false;
    }

    private String getLockKey(RedisLockable redisLock, Object[] arguments, Object target, Method targetMethod, String targetClassName, String methodName) {
        String prefix = redisLock.prefix();
        String[] keys = redisLock.key();
        StringBuilder stringBuilder = new StringBuilder("lock.");
        if (StringUtils.isNotBlank(prefix)) {
            stringBuilder.append(prefix);
        } else {
            stringBuilder.append(targetClassName).append(".").append(methodName);
        }
        if (ArrayUtils.isNotEmpty(keys)) {
            String keyExp = Joiner.on("+ '.' +").skipNulls().join(keys);
            SpELOperationContext operationContext = new SpELOperationContext(targetMethod, arguments, target.getClass(), target);
            Object expKey = generateKey(keyExp, operationContext);
            stringBuilder.append("#").append(expKey);
        }
        return stringBuilder.toString();
    }


}
