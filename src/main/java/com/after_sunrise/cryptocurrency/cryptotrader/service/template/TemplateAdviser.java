package com.after_sunrise.cryptocurrency.cryptotrader.service.template;

import com.after_sunrise.cryptocurrency.cryptotrader.framework.Adviser;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Context.Key;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Estimator.Estimation;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Order;
import com.after_sunrise.cryptocurrency.cryptotrader.framework.Request;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;
import static java.math.BigDecimal.*;
import static java.math.RoundingMode.*;
import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;

/**
 * @author takanori.takase
 * @version 0.0.1
 */
@Slf4j
public class TemplateAdviser implements Adviser {

    private static final BigDecimal EPSILON = ONE.movePointLeft(SCALE);

    private static final BigDecimal HALF = new BigDecimal("0.5");

    static final int SIGNUM_BUY = 1;

    static final int SIGNUM_SELL = -1;

    private final String id;

    public TemplateAdviser(String id) {
        this.id = id;
    }

    @Override
    public String get() {
        return id;
    }

    @Override
    public Advice advise(Context context, Request request, Estimation estimation) {

        BigDecimal weighedPrice = calculateWeighedPrice(context, request, estimation);

        BigDecimal basis = calculateBasis(context, request);

        BigDecimal bBasis = calculateBuyBasis(context, request, basis);

        BigDecimal sBasis = calculateSellBasis(context, request, basis);

        BigDecimal bPrice = calculateBuyLimitPrice(context, request, weighedPrice, bBasis);

        BigDecimal sPrice = calculateSellLimitPrice(context, request, weighedPrice, sBasis);

        BigDecimal bSize = calculateBuyLimitSize(context, request, bPrice);

        BigDecimal sSize = calculateSellLimitSize(context, request, sPrice);

        Advice advice = Advice.builder().buyLimitPrice(bPrice).buyLimitSize(bSize) //
                .sellLimitPrice(sPrice).sellLimitSize(sSize).build();

        log.trace("Advice : {} - {}", advice, request);

        return advice;

    }

    @VisibleForTesting
    BigDecimal calculateWeighedPrice(Context context, Request request, Estimation estimation) {

        BigDecimal confidence = estimation.getConfidence();

        if (estimation.getPrice() == null || confidence == null
                || confidence.signum() <= 0 || confidence.compareTo(ONE) > 0) {

            log.trace("Invalid estimation : {}", estimation);

            return null;

        }

        Key key = Key.from(request);

        BigDecimal mid = context.getMidPrice(key);

        if (mid == null) {

            log.trace("Weighed price not available. No mid.");

            return null;

        }

        BigDecimal estimate = estimation.getPrice();

        BigDecimal weighed = mid.multiply(ONE.subtract(confidence)).add(estimate.multiply(confidence));

        log.trace("Weighed price : {} (mid=[[]] [{}])", weighed, mid, estimation);

        return weighed;

    }

    @VisibleForTesting
    BigDecimal calculateBasis(Context context, Request request) {

        Key key = Key.from(request);

        BigDecimal comm = context.getCommissionRate(key);

        if (comm == null) {

            log.trace("Basis not available. Null commission.");

            return null;
        }

        BigDecimal spread = request.getTradingSpread();

        if (spread == null) {

            log.trace("Basis not available. Null spread.");

            return null;
        }

        return adjustBasis(context, request, spread.add(comm).add(comm));

    }

    protected BigDecimal adjustBasis(Context context, Request request, BigDecimal basis) {
        return basis;
    }

    @VisibleForTesting
    BigDecimal calculatePositionRatio(Context context, Request request) {

        Key key = Key.from(request);

        BigDecimal mid = context.getMidPrice(key);

        BigDecimal funding = context.getFundingPosition(key);

        BigDecimal structure = context.getInstrumentPosition(key);

        if (mid == null || funding == null || structure == null) {

            log.trace("Position ratio unavailable : price=[{}] funding=[{}] structure=[{}]", mid, funding, structure);

            return null;

        }

        BigDecimal offset = ofNullable(request.getFundingOffset()).orElse(ZERO);

        BigDecimal adjFunding = funding.multiply(ONE.add(offset));

        BigDecimal equivalent = structure.multiply(mid);

        BigDecimal ratio;

        if (Objects.equals(TRUE, context.isMarginable(key))) {

            // = Equivalent / (Funding / 2)
            // = 2 * Equivalent / Funding
            // (Funding / 2 = Funding for single side)

            if (adjFunding.signum() == 0) {
                return ZERO;
            }

            // Leveraged short can be larger than the funding.
            ratio = equivalent.add(equivalent).divide(adjFunding, SCALE, HALF_UP);

        } else {

            // = Diff / Average
            // = (X - Y) / [(X + Y) / 2]
            // = 2 * (X - Y) / (X + Y)

            BigDecimal sum = equivalent.add(adjFunding);

            if (sum.signum() == 0) {
                return ZERO;
            }

            BigDecimal diff = equivalent.subtract(adjFunding);

            ratio = diff.add(diff).divide(sum, SCALE, HALF_UP);

        }

        BigDecimal aversion = ofNullable(request.getTradingAversion()).orElse(ONE);

        BigDecimal aversionRatio = ratio.multiply(aversion).setScale(SCALE, HALF_UP);

        log.trace("Position ratio: {} (ratio=[{}], fund=[{}], structure=[{}] price=[{}])",
                aversionRatio, ratio, adjFunding, structure, mid);

        return aversionRatio;

    }

    @VisibleForTesting
    BigDecimal calculateRecentPrice(Context context, Request request, int signum) {

        Instant cutoff = request.getCurrentTime().minus(request.getTradingDuration());

        Key key = Key.from(request);

        List<Order.Execution> executions = ofNullable(context.listExecutions(key))
                .orElse(emptyList()).stream()
                .filter(Objects::nonNull)
                .filter(v -> v.getTime() != null)
                .filter(v -> v.getTime().isAfter(cutoff))
                .filter(v -> v.getPrice() != null)
                .filter(v -> v.getPrice().signum() != 0)
                .filter(v -> v.getSize() != null)
                .filter(v -> v.getSize().signum() != 0)
                .sorted(comparing(Order.Execution::getTime))
                .collect(Collectors.toList());

        List<BigDecimal[]> execs = new ArrayList<>();

        for (Order.Execution exec : executions) {

            BigDecimal price = exec.getPrice();

            BigDecimal size = exec.getSize();

            Iterator<BigDecimal[]> itr = execs.iterator();

            while (itr.hasNext()) {

                BigDecimal[] priceSize = itr.next();

                if (priceSize[1].signum() == size.signum()) {
                    break;
                }

                BigDecimal total = priceSize[1].add(size);

                if (priceSize[1].signum() == total.signum()) {

                    priceSize[1] = total;

                    size = ZERO;

                } else {

                    itr.remove();

                    size = total;

                }

            }

            if (size.signum() == 0) {
                continue;
            }

            execs.add(new BigDecimal[]{price, size});

        }

        if (execs.isEmpty()) {
            return null;
        }

        BigDecimal basis = ofNullable(calculateBasis(context, request)).orElse(ZERO);

        BigDecimal result = null;

        for (BigDecimal[] priceSize : execs) {

            BigDecimal size = priceSize[1];

            if (size.signum() != signum) {
                continue;
            }

            BigDecimal price = priceSize[0];

            if (size.signum() == SIGNUM_BUY) {

                price = price.multiply(ONE.add(basis));

                result = result == null ? price : price.max(result);

            }

            if (size.signum() == SIGNUM_SELL) {

                price = price.multiply(ONE.subtract(basis));

                result = result == null ? price : price.min(result);

            }

        }

        return result;

    }

    @VisibleForTesting
    BigDecimal calculateBuyLossRatio(Context context, Request request) {

        BigDecimal market = context.getBestBidPrice(Key.from(request));

        if (market == null) {
            return ZERO;
        }

        BigDecimal latest = calculateRecentPrice(context, request, SIGNUM_BUY);

        if (latest == null || latest.signum() == 0) {
            return ZERO;
        }

        BigDecimal lossPrice = latest.subtract(market).max(ZERO);

        BigDecimal lossRatio = lossPrice.divide(latest, SCALE, ROUND_UP);

        BigDecimal aversion = ofNullable(request.getTradingAversion()).orElse(ONE);

        return lossRatio.multiply(aversion).max(ZERO);

    }

    @VisibleForTesting
    BigDecimal calculateBuyBasis(Context context, Request request, BigDecimal base) {

        if (base == null) {
            return null;
        }

        BigDecimal positionRatio = ofNullable(calculatePositionRatio(context, request)).orElse(ZERO);

        BigDecimal positionBase = base.multiply(ONE.add(positionRatio.max(ZERO)));

        BigDecimal lossRatio = ofNullable(calculateBuyLossRatio(context, request)).orElse(ZERO);

        return positionBase.add(lossRatio);

    }

    @VisibleForTesting
    BigDecimal calculateSellLossRatio(Context context, Request request) {

        BigDecimal market = context.getBestAskPrice(Key.from(request));

        if (market == null) {
            return ZERO;
        }

        BigDecimal latest = calculateRecentPrice(context, request, SIGNUM_SELL);

        if (latest == null || latest.signum() == 0) {
            return ZERO;
        }

        BigDecimal lossPrice = market.subtract(latest).max(ZERO);

        BigDecimal lossRatio = lossPrice.divide(latest, SCALE, ROUND_UP);

        BigDecimal aversion = ofNullable(request.getTradingAversion()).orElse(ONE);

        return lossRatio.multiply(aversion).max(ZERO);

    }


    @VisibleForTesting
    BigDecimal calculateSellBasis(Context context, Request request, BigDecimal base) {

        if (base == null) {
            return null;
        }

        BigDecimal positionRatio = ofNullable(calculatePositionRatio(context, request)).orElse(ZERO);

        BigDecimal positionBase = base.multiply(ONE.add(positionRatio.min(ZERO).abs()));

        BigDecimal lossRatio = ofNullable(calculateSellLossRatio(context, request)).orElse(ZERO);

        return positionBase.add(lossRatio);

    }

    @VisibleForTesting
    BigDecimal calculateBuyBoundaryPrice(Context context, Request request) {

        Key key = Key.from(request);

        BigDecimal ask0 = context.getBestAskPrice(key);

        if (ask0 == null) {
            return null;
        }

        BigDecimal ask1 = context.roundTickSize(key, ask0.subtract(EPSILON), DOWN);

        if (ask1 == null) {
            return null;
        }

        BigDecimal recent = ofNullable(calculateRecentPrice(context, request, SIGNUM_SELL)).orElse(ask0);

        BigDecimal bid0 = ofNullable(context.getBestBidPrice(key)).orElse(ask0);

        BigDecimal bid1 = bid0;

        if (ofNullable(context.listActiveOrders(key)).orElse(emptyList()).stream()
                .filter(Objects::nonNull)
                .filter(o -> o.getOrderQuantity() != null)
                .filter(o -> o.getOrderQuantity().signum() == SIGNUM_BUY)
                .filter(o -> o.getOrderPrice() != null)
                .filter(o -> o.getOrderPrice().compareTo(bid0) == 0)
                .count() == 0) {

            bid1 = ofNullable(context.roundTickSize(key, bid0.add(EPSILON), UP)).orElse(bid0);

        }

        BigDecimal price = ask1.min(bid1).min(recent);

        return adjustBuyBoundaryPrice(context, request, price);

    }

    protected BigDecimal adjustBuyBoundaryPrice(Context context, Request request, BigDecimal price) {
        return price;
    }

    @VisibleForTesting
    BigDecimal calculateSellBoundaryPrice(Context context, Request request) {

        Key key = Key.from(request);

        BigDecimal bid0 = context.getBestBidPrice(key);

        if (bid0 == null) {
            return null;
        }

        BigDecimal bid1 = context.roundTickSize(key, bid0.add(EPSILON), UP);

        if (bid1 == null) {
            return null;
        }

        BigDecimal recent = ofNullable(calculateRecentPrice(context, request, SIGNUM_BUY)).orElse(bid0);

        BigDecimal ask0 = ofNullable(context.getBestAskPrice(key)).orElse(bid0);

        BigDecimal ask1 = ask0;

        if (ofNullable(context.listActiveOrders(key)).orElse(emptyList()).stream()
                .filter(Objects::nonNull)
                .filter(o -> o.getOrderQuantity() != null)
                .filter(o -> o.getOrderQuantity().signum() == SIGNUM_SELL)
                .filter(o -> o.getOrderPrice() != null)
                .filter(o -> o.getOrderPrice().compareTo(ask0) == 0)
                .count() == 0) {

            ask1 = ofNullable(context.roundTickSize(key, ask0.subtract(EPSILON), DOWN)).orElse(ask0);

        }

        BigDecimal price = bid1.max(ask1).max(recent);

        return adjustSellBoundaryPrice(context, request, price);

    }

    protected BigDecimal adjustSellBoundaryPrice(Context context, Request request, BigDecimal price) {
        return price;
    }

    @VisibleForTesting
    BigDecimal calculateBuyLimitPrice(Context context, Request request, BigDecimal weighedPrice, BigDecimal basis) {

        if (weighedPrice == null || basis == null) {

            log.trace("Buy price not available : weighed=[{}] basis=[{}]", weighedPrice, basis);

            return null;

        }

        Key key = Key.from(request);

        BigDecimal bound = calculateBuyBoundaryPrice(context, request);

        if (bound == null) {

            log.trace("Buy price not available : No bound price.");

            return null;

        }

        BigDecimal basisPrice = weighedPrice.multiply(ONE.subtract(basis));

        BigDecimal boundPrice = basisPrice.min(bound);

        BigDecimal rounded = context.roundTickSize(key, boundPrice, DOWN);

        log.trace("Buy price : {} (target=[{}] basis=[{}])", rounded, boundPrice, basisPrice);

        return rounded;

    }

    @VisibleForTesting
    BigDecimal calculateSellLimitPrice(Context context, Request request, BigDecimal weighedPrice, BigDecimal basis) {

        if (weighedPrice == null || basis == null) {

            log.trace("Sell price not available : weighed=[{}] basis=[{}]", weighedPrice, basis);

            return null;

        }

        Key key = Key.from(request);

        BigDecimal bound = calculateSellBoundaryPrice(context, request);

        if (bound == null) {

            log.trace("Sell price not available : No bound price.");

            return null;

        }

        BigDecimal basisPrice = weighedPrice.multiply(ONE.add(basis));

        BigDecimal boundPrice = basisPrice.max(bound);

        BigDecimal rounded = context.roundTickSize(key, boundPrice, UP);

        log.trace("Sell price : {} (target=[{}] basis=[{}])", rounded, boundPrice, basisPrice);

        return rounded;

    }

    @VisibleForTesting
    BigDecimal calculateFundingExposureSize(Context context, Request request, BigDecimal price) {

        if (price == null || price.signum() == 0) {

            log.trace("No funding exposure size. Price : {}", price);

            return ZERO;

        }

        Key key = Key.from(request);

        BigDecimal fund = context.getFundingPosition(key);

        if (fund == null) {

            log.trace("No funding exposure size. Null funding position.");

            return ZERO;

        }

        BigDecimal offset = ofNullable(request.getFundingOffset()).orElse(ZERO);

        BigDecimal adjFund = fund.multiply(ONE.add(offset));

        BigDecimal product = adjFund.divide(price, SCALE, HALF_UP);

        BigDecimal exposure = ofNullable(request.getTradingExposure()).orElse(ZERO);

        BigDecimal exposed = product.multiply(exposure);

        log.trace("Funding exposure size : {} (fund=[{}] price=[{}])", exposed, adjFund, price);

        return exposed;

    }

    @VisibleForTesting
    BigDecimal calculateInstrumentExposureSize(Context context, Request request) {

        Key key = Key.from(request);

        BigDecimal position = context.getInstrumentPosition(key);

        if (position == null) {

            log.trace("No instrument exposure size. Null instrument position.");

            return ZERO;

        }

        BigDecimal exposure = ofNullable(request.getTradingExposure()).orElse(ZERO);

        BigDecimal exposed = position.multiply(exposure);

        log.trace("Instrument exposure size : {} (position=[{}])", exposed, position);

        return exposed;

    }

    @VisibleForTesting
    BigDecimal calculateBuyLimitSize(Context context, Request request, BigDecimal price) {

        BigDecimal fundingSize = calculateFundingExposureSize(context, request, price);

        BigDecimal instrumentSize = calculateInstrumentExposureSize(context, request);

        BigDecimal size;

        if (Objects.equals(TRUE, context.isMarginable(Key.from(request)))) {

            size = fundingSize.subtract(instrumentSize).max(ZERO).multiply(HALF);

        } else {

            BigDecimal excess = instrumentSize.subtract(fundingSize).max(ZERO).multiply(HALF);

            size = fundingSize.subtract(excess).max(ZERO);

        }

        BigDecimal rounded = context.roundLotSize(Key.from(request), size, HALF_UP);

        log.trace("Buy size : {} (funding=[{}] instrument[{}])", rounded, fundingSize, instrumentSize);

        return adjustBuyLimitSize(context, request, ofNullable(rounded).orElse(ZERO));

    }

    protected BigDecimal adjustBuyLimitSize(Context context, Request request, BigDecimal size) {
        return size;
    }

    @VisibleForTesting
    BigDecimal calculateSellLimitSize(Context context, Request request, BigDecimal price) {

        BigDecimal instrumentSize = calculateInstrumentExposureSize(context, request);

        BigDecimal fundingSize = calculateFundingExposureSize(context, request, price);

        BigDecimal size;

        if (Objects.equals(TRUE, context.isMarginable(Key.from(request)))) {

            size = fundingSize.add(instrumentSize).max(ZERO).multiply(HALF);

        } else {

            BigDecimal excess = fundingSize.subtract(instrumentSize).max(ZERO).multiply(HALF);

            size = instrumentSize.subtract(excess).max(ZERO);

        }

        BigDecimal rounded = context.roundLotSize(Key.from(request), size, HALF_UP);

        log.trace("Sell size : {} (funding=[{}] instrument[{}])", rounded, fundingSize, instrumentSize);

        return adjustSellLimitSize(context, request, ofNullable(rounded).orElse(ZERO));

    }

    protected BigDecimal adjustSellLimitSize(Context context, Request request, BigDecimal size) {
        return size;
    }

}
