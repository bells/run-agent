package cn.watsonzhu.runagent.marathon;

import java.util.Optional;

import cn.watsonzhu.runagent.marathon.MarathonData.Distance;
import cn.watsonzhu.runagent.marathon.MarathonData.Event;
import cn.watsonzhu.runagent.marathon.MarathonData.Podium;
import cn.watsonzhu.runagent.marathon.MarathonData.Series;

@FunctionalInterface
public interface MarathonResultLookup {
    Optional<Podium> lookup(Series series, Event event, int year, Distance distance, long deadlineNanos);
}
