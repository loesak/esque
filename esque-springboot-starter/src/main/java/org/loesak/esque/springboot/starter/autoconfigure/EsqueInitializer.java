package org.loesak.esque.springboot.starter.autoconfigure;

import org.loesak.esque.core.Esque;
import org.springframework.beans.factory.InitializingBean;

public class EsqueInitializer  implements InitializingBean {

    private final Esque esque;

    public EsqueInitializer(Esque esque) {
        this.esque = esque;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.esque.execute();
    }
}
