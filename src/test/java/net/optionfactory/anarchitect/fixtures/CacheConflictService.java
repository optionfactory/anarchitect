package net.optionfactory.anarchitect.fixtures;

import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class CacheConflictService {

    @Cacheable("cache")
    @CachePut("cache")
    public String conflicting(String key) {
        return key;
    }

    @Cacheable("other")
    public String plain(String key) {
        return key;
    }
}
