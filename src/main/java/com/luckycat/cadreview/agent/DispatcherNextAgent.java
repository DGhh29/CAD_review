package com.luckycat.cadreview.agent;

/**
 * Dispatcher 每轮调度后选择的下一类执行者。
 */
public enum DispatcherNextAgent {
    /** 继续下发任务给 Reviewer 执行。 */
    REVIEWER,

    /** 证据已足够或不再适合继续拆任务，进入 Summarizer 收口。 */
    SUMMARIZER
}
