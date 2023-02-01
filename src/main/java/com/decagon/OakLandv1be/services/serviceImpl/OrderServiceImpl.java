package com.decagon.OakLandv1be.services.serviceImpl;

import com.decagon.OakLandv1be.dto.OrderRequestDto;
import com.decagon.OakLandv1be.dto.OrderResponseDto;

import com.decagon.OakLandv1be.entities.*;
import com.decagon.OakLandv1be.enums.PaymentPurpose;
import com.decagon.OakLandv1be.enums.TransactionStatus;

import com.decagon.OakLandv1be.entities.Customer;
import com.decagon.OakLandv1be.entities.Order;
import com.decagon.OakLandv1be.enums.DeliveryStatus;

import com.decagon.OakLandv1be.exceptions.EmptyListException;
import com.decagon.OakLandv1be.exceptions.ResourceNotFoundException;
import com.decagon.OakLandv1be.repositries.CustomerRepository;
import com.decagon.OakLandv1be.repositries.OrderRepository;
import com.decagon.OakLandv1be.repositries.PickupRepository;
import com.decagon.OakLandv1be.repositries.TransactionRepository;
import com.decagon.OakLandv1be.services.CartService;
import com.decagon.OakLandv1be.services.CustomerService;
import com.decagon.OakLandv1be.services.OrderService;
import com.decagon.OakLandv1be.services.PickupService;
import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final CustomerService customerService;
    private final TransactionRepository transactionRepository;
    private final CartService cartService;
    private final PickupRepository pickupRepository;

    @Override
    public List<OrderResponseDto> viewOrderHistory(int pageNo, int pageSize) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if ((authentication instanceof AnonymousAuthenticationToken))
            throw new ResourceNotFoundException("Please Login");
        String email = authentication.getName();
        Customer customer = customerRepository.findByPersonEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Set<Order> orderList = customer.getOrders();
        if (orderList.isEmpty())
            throw new EmptyListException("You have no order history");

        List<Order> sortedOrders = orderList.stream().sorted(Comparator.comparing(Order::getCreatedAt)
                .reversed()).collect(Collectors.toList());

        Pageable pageable = PageRequest.of(pageNo, pageSize);
        int start = (int)pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), sortedOrders.size());
        List<Order> orders = new ArrayList<>(sortedOrders).subList(start, end);

        List<OrderResponseDto> orderResponseDtos = new ArrayList<>();
        for (Order order: orders){
            mapToResponse(order,orderResponseDtos);
        }
        return orderResponseDtos;
    }

    private static void mapToResponse(Order order, List<OrderResponseDto> orderResponseDtos) {
        OrderResponseDto orderResponseDto = OrderResponseDto.builder()
                .items(order.getItems())
                .address(order.getAddress())
                .deliveryStatus(DeliveryStatus.TO_ARRIVE)
                .modeOfDelivery(order.getModeOfDelivery())
                .modeOfPayment(order.getModeOfPayment())
                .discount(order.getDiscount())
                .deliveryFee(order.getDeliveryFee())
                .grandTotal(order.getGrandTotal())
                .build();
        orderResponseDtos.add(orderResponseDto);
    }


    @Override
    public OrderResponseDto viewAParticularOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return OrderResponseDto.builder()
                .modeOfPayment(order.getModeOfPayment())
                .items(order.getItems())
                .deliveryFee(order.getDeliveryFee())
                .modeOfDelivery(order.getModeOfDelivery())
                .deliveryStatus(DeliveryStatus.TO_ARRIVE)
                .grandTotal(order.getGrandTotal())
                .discount(order.getDiscount())
                .address(order.getAddress())
                .build();
    }

    @Override
    public Page<OrderResponseDto> viewAllOrdersPaginated(Integer pageNo, Integer pageSize, String sortBy, boolean isAscending) {
        List<Order> orders = orderRepository.findAll();
        if(orders.isEmpty()){
            throw new EmptyListException("Sorry there are no Customer orders yet");
        }
        List<OrderResponseDto> orderResponseDtos =
                orders.stream()
                        .map(order -> OrderResponseDto.builder()
                                .modeOfPayment(order.getModeOfPayment())
                                .items(order.getItems())
                                .deliveryFee(order.getDeliveryFee())
                                .modeOfDelivery(order.getModeOfDelivery())
                                .deliveryStatus(DeliveryStatus.TO_ARRIVE)
                                .grandTotal(order.getGrandTotal())
                                .discount(order.getDiscount())
                                .address(order.getAddress())
                .build()).collect(Collectors.toList());

        PageRequest pageable = PageRequest.of(pageNo, pageSize, Sort.Direction.DESC, sortBy);
        int max = Math.min(pageSize * (pageNo + 1), orderResponseDtos.size());
        return new PageImpl<>(orderResponseDtos.subList(pageNo*pageSize, max), pageable, orderResponseDtos.size());
    }


    @Override
    public String saveOrder(OrderRequestDto orderRequestDto) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if ((authentication instanceof AnonymousAuthenticationToken))
            throw new ResourceNotFoundException("Please Login");
        String email = authentication.getName();
        Customer loggedInCustomer = customerRepository.findByPersonEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Transaction transaction = Transaction.builder()
                .wallet(loggedInCustomer.getWallet())
                .amount(orderRequestDto.getGrandTotal().toString())
                .reference(UUID.randomUUID().toString())
                .purpose(PaymentPurpose.PURCHASE)
                .status(TransactionStatus.PENDING)
                .build();

        PickupCenter pickupCenter = pickupRepository
                .findByEmail(orderRequestDto.getPickupCenterEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Pickup center not found"));

        BigDecimal bigDecimalValue = orderRequestDto.getGrandTotal();
        double total = bigDecimalValue.doubleValue();

        Set<Item> allCartItems = loggedInCustomer.getCart().getItems();
        Set<OrderItem> orderItems = new HashSet<>();


        allCartItems.forEach(item -> {
            OrderItem orderItem = OrderItem.builder()
                    .orderQty(item.getOrderQty())
                    .order(item.getOrder())
                    .productName(item.getProductName())
                    .subTotal(item.getSubTotal())
                    .unitPrice(item.getUnitPrice())
                    .product(item.getProduct())
                    .build();
            orderItems.add(orderItem);

        });

        cartService.clearCart();

        Order order = Order.builder()
                .grandTotal(total)
                .pickupCenter(pickupCenter)
                .transaction(transaction)
                .customer(loggedInCustomer)
                .items(orderItems)
                .build();
        orderRepository.save(order);

        transaction.setOrder(order);
        transactionRepository.save(transaction);

        return "New Order made successfully";
    }

        @Override
        public Page<OrderResponseDto> getOrderByDeliveryStatus(DeliveryStatus status, Integer pageNo, Integer pageSize){
            pageNo=Math.max(pageNo,0);
            pageSize=Math.max(pageSize,10);
            Pageable pageable=PageRequest.of(pageNo,pageSize);
            return orderRepository.findByDeliveryStatus(status,pageable).map(this::orderResponseMapper);
        }


    private OrderResponseDto orderResponseMapper(Order order){
        return OrderResponseDto.builder()
                .modeOfPayment(order.getModeOfPayment())
                .items(order.getItems())
                .deliveryFee(order.getDeliveryFee())
                .modeOfDelivery(order.getModeOfDelivery())
                .deliveryStatus(order.getDeliveryStatus())
                .grandTotal(order.getGrandTotal())
                .discount(order.getDiscount())
                .address(order.getAddress())
                .build();
    }
}
