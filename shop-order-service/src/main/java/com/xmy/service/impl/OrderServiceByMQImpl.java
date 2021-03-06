package com.xmy.service.impl;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.xmy.api.ICouponService;
import com.xmy.api.IGoodsService;
import com.xmy.api.IOrderService;
import com.xmy.api.IUserService;
import com.xmy.constant.ShopCode;
import com.xmy.entity.MQEntity;
import com.xmy.entity.OrderResult;
import com.xmy.entity.Result;
import com.xmy.exception.CastException;
import com.xmy.mapper.ShopMsgProviderMapper;
import com.xmy.mapper.ShopOrderMapper;
import com.xmy.mapper.ShopOrderMqStatusLogMapper;
import com.xmy.pojo.*;
import com.xmy.utils.IDWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @author xmy
 * @date 2019-10-09 21:49
 */
@SuppressWarnings("ALL")
@Slf4j
@Component
@Service(interfaceClass = IOrderService.class)
public class OrderServiceByMQImpl implements IOrderService {

    @Reference
    private IGoodsService goodsService;

    @Reference
    private IUserService userService;

    @Reference
    private ICouponService couponService;

    @Autowired
    private ShopOrderMapper orderMapper;

    @Autowired
    private ShopMsgProviderMapper msgProviderMapper;

    @Autowired
    private ShopOrderMqStatusLogMapper orderMqStatusLogMapper;

    @Autowired
    private IDWorker idWorker;

    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Autowired
    private DefaultMQProducer producer;

    @Autowired
    private TransactionMQProducer transactionMQProducer;

    @Value("${mq.rocketmq.producer.group}")
    private String groupName;

    @Value("${mq.order.topic}")
    private String topic;

    @Value("${mq.order.tag.cancel}")
    private String tag;

    @Value("${mq.goods.topic}")
    private String goodsTopic;

    @Value("${mq.goods.tag.reduce}")
    private String reduceGoodsNumTag;


    @Value("${mq.order.confirm.topic}")
    private String orderConfirmTopic;

    @Value("${mq.order.confirm.tag.confirm}")
    private String orderConfirmTag;

    @Override
    public Result confirmOrder(ShopOrder order) {
        // 1 校验订单
        checkOrder(order);
        // 2 生成预订单
        Long orderId = savePreOrder(order);
        Result result = null;
        String message = ShopCode.SHOP_ORDER_CONFIRM_FAIL.getMessage();
        MQEntity mqEntity = null;
        try {
            mqEntity = new MQEntity();
            mqEntity.setOrderId(orderId);
            mqEntity.setUserId(order.getUserId());
            mqEntity.setGoodsId(order.getGoodsId());
            mqEntity.setCouponId(order.getCouponId());
            // 3 MQ监听 扣减优惠券
            // 4 MQ监听 使用余额
            // 5 MQ监听 扣减库存
            ShopOrder orderResult = orderMapper.selectByPrimaryKey(orderId);
            Boolean status = true;
            while (status) {
                if (orderResult.getOrderStatus().intValue() == ShopCode.SHOP_ORDER_CONFIRM.getCode()) {
                    status = false;
                    result = new Result(ShopCode.SHOP_SUCCESS.getSuccess(), ShopCode.SHOP_ORDER_CONFIRM.getCode(), JSON.toJSONString(orderResult));
                }
                if (orderResult.getOrderStatus().intValue() == ShopCode.SHOP_ORDER_CALL_ERROR.getCode()) {
                    ShopOrderMqStatusLog orderMqStatusLog = orderMqStatusLogMapper.selectByPrimaryKey(orderId);
                    if (orderMqStatusLog.getGoodsStatus() == 1) {
                        mqEntity.setGoodsNumber(order.getGoodsNumber());
                    } else {
                        mqEntity.setGoodsNumber(0);
                    }
                    if (orderMqStatusLog.getUserMoneyStatus() == 1) {
                        mqEntity.setUserMoney(order.getMoneyPaid());
                    } else {
                        mqEntity.setUserMoney(BigDecimal.ZERO);
                    }
                    CastException.cast(ShopCode.SHOP_ORDER_CALL_ERROR);
                }
                orderResult = orderMapper.selectByPrimaryKey(orderId);
            }
            // 7 返回成功状态
            return result;

        } catch (Exception e) {
            log.info(e.toString());
            /** 失败补偿机制 **/
            // 1 确认订单失败,发送消息
            //订单ID 优惠券ID 用户ID 余额  商品ID 商品数量

            try {
                sendCancelOrder(topic, tag, order.getOrderId().toString(), JSON.toJSONString(mqEntity));
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            // 2 返回失败状态
            result = new Result(ShopCode.SHOP_FAIL.getSuccess(), message);
            return result;
        }
    }

    @Override
    public Result cancelOrder(ShopOrder order) {
        Result result = null;
        // 参数校验
        if (order == null || order.getOrderId() == null ||
                order.getUserId() == null ||
                order.getGoodsId() == null ||
                order.getGoodsNumber().intValue() <= 0) {
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        // 查询订单
        ShopOrder shopOrder = orderMapper.selectByPrimaryKey(order.getOrderId());
        // 判断订单是否能取消
        if (shopOrder != null && shopOrder.getOrderStatus() < 2 && shopOrder.getShippingStatus() < 1) {
            MQEntity mqEntity = new MQEntity();
            mqEntity.setOrderId(shopOrder.getOrderId());
            mqEntity.setUserId(shopOrder.getUserId());
            mqEntity.setGoodsId(shopOrder.getGoodsId());
            mqEntity.setGoodsNumber(shopOrder.getGoodsNumber());
            mqEntity.setUserMoney(shopOrder.getMoneyPaid());
            mqEntity.setCouponId(shopOrder.getCouponId());
            try {
                // 发送取消订单消息
                sendCancelOrder(topic, tag, shopOrder.getOrderId().toString(), JSON.toJSONString(mqEntity));
                OrderResult orderResult = new OrderResult();
                orderResult.setMessage(ShopCode.SHOP_ORDER_CANCEL_CHECK.getMessage());
                orderResult.setOrderId(shopOrder.getOrderId());
                orderResult.setStatus(ShopCode.SHOP_SUCCESS.getSuccess());
                orderResult.setPayAmount(shopOrder.getPayAmount());
                result = new Result(ShopCode.SHOP_SUCCESS.getSuccess(), ShopCode.SHOP_FAIL.getMessage());
            } catch (Exception e1) {
                e1.printStackTrace();
                result = new Result(ShopCode.SHOP_FAIL.getSuccess(), ShopCode.SHOP_FAIL.getMessage());
            }
        } else {
            result = new Result(ShopCode.SHOP_FAIL.getSuccess(), ShopCode.SHOP_ORDER_CANCEL_ERROR.getMessage());
        }
        return result;
    }

    private void sendConfirmOrder(ShopOrder order) throws Exception {
        // 将消息持久化到数据库
//        ShopMsgProvider msgProvider = new ShopMsgProvider();
//        msgProvider.setId(String.valueOf(idWorker.nextId()));
//        msgProvider.setGroupName(groupName);
//        msgProvider.setMsgTopic(orderConfirmTopic);
//        msgProvider.setMsgTag(orderConfirmTag);
//        msgProvider.setMsgKey(String.valueOf(order.getOrderId()));
//        msgProvider.setMsgBody(JSON.toJSONString(order));
//        msgProvider.setCreateTime(new Date());
//        msgProviderMapper.insert(msgProvider);
//        log.info("订单服务,持久化订单消息到库");

        Message messageOrderConfim = new Message(orderConfirmTopic, orderConfirmTag, order.getOrderId().toString(), JSON.toJSONString(order).getBytes());
        SendResult transactionSendResult = transactionMQProducer.sendMessageInTransaction(messageOrderConfim, orderConfirmTag);
        if (transactionSendResult.getSendStatus().equals(SendStatus.SEND_OK)) {
            //6 等待发送结果,如果MQ接受到消息,删除发送成功的消息
//            log.info("订单服务,订单消息发送成功");
//            ShopMsgProviderKey msgProviderKey = new ShopMsgProviderKey();
//            msgProviderKey.setGroupName(groupName);
//            msgProviderKey.setMsgKey(String.valueOf(order.getOrderId()));
//            msgProviderKey.setMsgTag(orderConfirmTag);
//            msgProviderMapper.deleteByPrimaryKey(msgProviderKey);
//            log.info("订单服务,数据库中持久化订单消息已删除");
            log.info("订单:" + order.getOrderId() + ",订单发送成功");
        } else {
            CastException.cast(ShopCode.SHOP_ORDER_ERROR);
        }

    }

    /**
     * 发送订单确认失败消息
     *
     * @param topic
     * @param tag
     * @param keys
     * @param body
     */
    private void sendCancelOrder(String topic, String tag, String keys, String body) throws Exception {
        Message message = new Message(topic, tag, keys, body.getBytes());
        producer.send(message);
    }

    private void checkOrder(ShopOrder order) {
        // 校验订单是否存在
        if (order == null) {
            CastException.cast(ShopCode.SHOP_ORDER_INVALID);
        }
        // 校验订单商品是否存在
        ShopGoods goods = goodsService.findOne(order.getGoodsId());
        if (goods == null) {
            CastException.cast(ShopCode.SHOP_GOODS_NO_EXIST);
        }
        // 校验下单用户是否存在
        ShopUser user = userService.findOne(order.getUserId());
        if (user == null) {
            CastException.cast(ShopCode.SHOP_USER_NO_EXIST);
        }
        // 校验订单商品单价是否合法
        if (order.getGoodsPrice().compareTo(goods.getGoodsPrice()) != 0) {
            CastException.cast(ShopCode.SHOP_GOODS_PRICE_INVALID);
        }
        // 校验订单商品数量是否合法
        if (order.getGoodsNumber() >= goods.getGoodsNumber()) {
            CastException.cast(ShopCode.SHOP_GOODS_NUM_NOT_ENOUGH);
        }
        log.info("校验订单通过");
    }

    /**
     * 生成预订单
     *
     * @param order
     * @return
     */
    private Long savePreOrder(ShopOrder order) {
        //1 设置订单状态为不可见
        order.setOrderStatus(ShopCode.SHOP_ORDER_NO_CONFIRM.getCode());
        //2 设置订单ID
        long orderId = idWorker.nextId();
        order.setOrderId(orderId);
        //3 核算订单运费
        BigDecimal shippingFee = calculateShippingFee(order.getOrderAmount());
        if (order.getShippingFee().compareTo(shippingFee) != 0) {
            CastException.cast(ShopCode.SHOP_ORDER_SHIPPINGFEE_INVALID);
        }
        //4 核算订单总金额是否合法
        BigDecimal orderAmount = order.getGoodsPrice().multiply(BigDecimal.valueOf(order.getGoodsNumber()));
        orderAmount.add(shippingFee);
        if (order.getOrderAmount().compareTo(orderAmount) != 0) {
            CastException.cast(ShopCode.SHOP_ORDERMOUNT_INVALID);
        }
        //5 判断用户是否使用余额
        BigDecimal moneyPaid = order.getMoneyPaid();
        if (moneyPaid != null) {
            //5.1 订单中余额是否合法
            int r = moneyPaid.compareTo(BigDecimal.ZERO);
            // 余额小于0
            if (r == -1) {
                CastException.cast(ShopCode.SHOP_MONEY_PAID_LESS_ZERO);
            }
            // 余额大于0
            if (r == 1) {
                ShopUser user = userService.findOne(order.getUserId());
                // 判断余额是否超出
                if (moneyPaid.compareTo(user.getUserMoney()) == 1) {
                    // 余额超出
                    CastException.cast(ShopCode.SHOP_MONEY_PAID_INVALIS);
                }
            }
        } else {
            // 防空指针
            order.setMoneyPaid(BigDecimal.ZERO);
        }
        //6 判断用户是否使用优惠券
        Long couponId = order.getCouponId();
        if (couponId != null) {
            ShopCoupon coupon = couponService.findOne(couponId);
            //6.1 判断优惠券是否存在
            if (coupon == null) {
                CastException.cast(ShopCode.SHOP_COUPON_NO_EXIST);
            }
            //6.2 判断优惠券是否已使用
            if (coupon.getIsUsed().intValue() == ShopCode.SHOP_COUPON_ISUSED.getCode().intValue()) {
                CastException.cast(ShopCode.SHOP_COUPON_ISUSED);
            }
            //设置优惠券金额
            order.setCouponPaid(coupon.getCouponPrice());
        } else {
            // 防空指针
            order.setCouponPaid(BigDecimal.ZERO);
        }
        //7 核算订单支付金额 订单总金额 - 余额 - 优惠券金额
        BigDecimal payAmount = order.getOrderAmount().subtract(order.getMoneyPaid()).subtract(order.getCouponPaid());
        order.setPayAmount(payAmount);
        //8 设置下单时间
        order.setAddTime(new Date());
        //9 保存订单到数据库
        try {
            sendConfirmOrder(order);
        } catch (Exception e) {
            e.printStackTrace();
            CastException.cast(ShopCode.SHOP_ORDER_ERROR);
        }
        log.info("生成预订单");
        //10 返回订单ID
        return orderId;
    }

    /**
     * 核算运费
     *
     * @param orderAmount
     * @return
     */
    private BigDecimal calculateShippingFee(BigDecimal orderAmount) {
        if (orderAmount.compareTo(BigDecimal.valueOf(100)) == 1) {
            return BigDecimal.ZERO;
        } else {
            return BigDecimal.valueOf(10);
        }
    }

}
