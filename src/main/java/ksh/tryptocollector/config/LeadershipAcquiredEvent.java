package ksh.tryptocollector.config;

import org.springframework.context.ApplicationEvent;

public class LeadershipAcquiredEvent extends ApplicationEvent {
    public LeadershipAcquiredEvent(Object source) {
        super(source);
    }
}
