package com.shenqianye.samsara.lottery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 乐透票数：一次下注可以包含多张票，旧数据缺省按 1 张处理。 */
class LotteryTicketCountTest {
    @Test
    void missingTicketsDefaultsToOne() {
        assertEquals(1, LotteryService.ticketsOf(new LotteryService.BetData()));
        LotteryService.BetData zero = new LotteryService.BetData();
        zero.tickets = 0;
        assertEquals(1, LotteryService.ticketsOf(zero));
    }

    @Test
    void totalTicketsSumsAllBets() {
        LotteryService.DayData day = new LotteryService.DayData();
        LotteryService.BetData first = new LotteryService.BetData();
        first.tickets = 4;
        LotteryService.BetData second = new LotteryService.BetData();
        second.tickets = 2;
        day.bets.add(first);
        day.bets.add(second);
        assertEquals(6, LotteryService.totalTickets(day));
    }

    @Test
    void totalTicketsTreatsLegacyBetAsOne() {
        LotteryService.DayData day = new LotteryService.DayData();
        LotteryService.BetData legacy = new LotteryService.BetData();
        legacy.tickets = 0;
        day.bets.add(legacy);
        assertEquals(1, LotteryService.totalTickets(day));
    }
}
