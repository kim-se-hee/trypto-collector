package ksh.tryptocollector.config;

import org.springframework.context.ApplicationEvent;

public class LeadershipRevokedEvent extends ApplicationEvent {
    public LeadershipRevokedEvent(Object source) {
        super(source);
    }
}
