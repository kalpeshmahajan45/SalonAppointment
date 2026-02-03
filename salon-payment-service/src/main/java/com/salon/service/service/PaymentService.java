package com.salon.service.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.salon.service.dao.PgTransactionDao;
import com.salon.service.dto.Booking;
import com.salon.service.dto.CommanApiResponse;
import com.salon.service.dto.OrderRazorPayResponse;
import com.salon.service.external.BookingService;
import com.salon.service.external.UserService;
import com.salon.service.model.PgTransaction;
import com.salon.service.pg.Notes;
import com.salon.service.pg.Prefill;
import com.salon.service.pg.RazorPayPaymentRequest;
import com.salon.service.pg.RazorPayPaymentResponse;
import com.salon.service.pg.Theme;
import com.salon.service.utility.Constants.PaymentGatewayTxnStatus;
import com.salon.service.utility.Constants.PaymentGatewayTxnType;
import com.salon.service.utility.Constants.ResponseCode;

@Service
public class PaymentService {

	Logger LOG = LoggerFactory.getLogger(PaymentService.class);

	@Autowired
	private PgTransactionDao pgTransactionDao;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private UserService userService;

	@Value("${com.salon.paymentGateway.razorpay.key}")
	private String razorPayKey;

	@Value("${com.salon.paymentGateway.razorpay.secret}")
	private String razorPaySecret;

	// ===================== CREATE ORDER (UNCHANGED) =====================
	public ResponseEntity<OrderRazorPayResponse> createRazorPayOrder(Booking booking) throws RazorpayException {

		OrderRazorPayResponse response = new OrderRazorPayResponse();

		if (booking.getUserId() == 0) {
			response.setResponseMessage("bad request - user id is missing");
			response.setResponseCode(ResponseCode.FAILED.value());
			return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
		}

		String requestTime = String.valueOf(
				LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

		BigDecimal totalPrice = new BigDecimal(booking.getPrice());
		String receiptId = generateUniqueRefId();

		RazorpayClient razorpay = new RazorpayClient(razorPayKey, razorPaySecret);

		JSONObject orderRequest = new JSONObject();
		orderRequest.put("amount", convertRupeesToPaisa(totalPrice));
		orderRequest.put("currency", "INR");
		orderRequest.put("receipt", receiptId);

		Order order = razorpay.orders.create(orderRequest);
		String orderId = order.get("id");

		PgTransaction createOrder = new PgTransaction();
		createOrder.setAmount(totalPrice);
		createOrder.setReceiptId(receiptId);
		createOrder.setRequestTime(requestTime);
		createOrder.setType(PaymentGatewayTxnType.CREATE_ORDER.value());
		createOrder.setUserId(booking.getUserId());
		createOrder.setOrderId(orderId);
		createOrder.setBookingId(booking.getId());
		createOrder.setStatus(PaymentGatewayTxnStatus.SUCCESS.value());
		pgTransactionDao.save(createOrder);

		PgTransaction payment = new PgTransaction();
		payment.setAmount(totalPrice);
		payment.setReceiptId(receiptId);
		payment.setRequestTime(requestTime);
		payment.setType(PaymentGatewayTxnType.PAYMENT.value());
		payment.setUserId(booking.getUserId());
		payment.setOrderId(orderId);
		payment.setBookingId(booking.getId());
		payment.setStatus(PaymentGatewayTxnStatus.FAILED.value());
		pgTransactionDao.save(payment);

		RazorPayPaymentRequest razorPayPaymentRequest = new RazorPayPaymentRequest();
		razorPayPaymentRequest.setAmount(convertRupeesToPaisa(totalPrice));
		razorPayPaymentRequest.setCurrency("INR");
		razorPayPaymentRequest.setDescription("Salon Slot Booking Payment - Salon Booking System");
		razorPayPaymentRequest.setImage("https://thumbs.dreamstime.com/b/salon-concept-logo-26458280.jpg");
		razorPayPaymentRequest.setKey(razorPayKey);
		razorPayPaymentRequest.setName("Salon Booking System");
		razorPayPaymentRequest.setOrderId(orderId);

		Prefill prefill = new Prefill();
		prefill.setContact(booking.getCustomerContact());
		prefill.setEmail(booking.getCustomerEmailId());
		prefill.setName(booking.getCustomerFirstName() + " " + booking.getCustomerLastName());
		razorPayPaymentRequest.setPrefill(prefill);

		Theme theme = new Theme();
		theme.setColor("#D4AA00");
		razorPayPaymentRequest.setTheme(theme);

		response.setBooking(booking);
		response.setRazorPayRequest(razorPayPaymentRequest);
		response.setResponseMessage("Payment Order Created Successful!!!");
		response.setResponseCode(ResponseCode.SUCCESS.value());

		return new ResponseEntity<>(response, HttpStatus.OK);
	}

	// ===================== PAYMENT RESPONSE (FIXED) =====================
	public ResponseEntity<CommanApiResponse> handleRazorPayPaymentResponse(
        RazorPayPaymentResponse razorPayResponse) {

    CommanApiResponse response = new CommanApiResponse();

    // 1. Validate the response
    if (razorPayResponse == null || razorPayResponse.getRazorpayOrderId() == null) {
        response.setResponseMessage("Invalid Razorpay response");
        response.setResponseCode(ResponseCode.FAILED.value());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    PgTransaction paymentTransaction = pgTransactionDao.findByTypeAndOrderId(
            PaymentGatewayTxnType.PAYMENT.value(),
            razorPayResponse.getRazorpayOrderId()
    );

    // 2. Update Payment Status to Success
    paymentTransaction.setStatus(PaymentGatewayTxnStatus.SUCCESS.value());
    pgTransactionDao.save(paymentTransaction);

    // 3. Get Booking details from the response
    Booking booking = razorPayResponse.getBooking();

    // ✅ FIX 1: Explicitly set status to APPROVED before sending to Booking Service
    booking.setStatus("Approved"); 

    // ✅ FIX 2: Confirm Booking Status in the Booking Microservice
    CommanApiResponse bookingResponse = bookingService.updateBookingAfterPayment(booking);


    if (bookingResponse == null || bookingResponse.getResponseCode() != ResponseCode.SUCCESS.value()) {
        response.setResponseMessage("Booking confirmation failed");
        response.setResponseCode(ResponseCode.FAILED.value());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ✅ FIX 3: Ensure the wallet update uses the salonUserId
    // LOG here to verify: System.out.println("Updating wallet for ID: " + booking.getSalonUserId());
    CommanApiResponse walletResponse = userService.updateSalonWallet(
            booking.getSalonUserId(), 
            paymentTransaction.getAmount()
    );

    if (walletResponse == null || walletResponse.getResponseCode() != ResponseCode.SUCCESS.value()) {
        response.setResponseMessage("Booking Approved but Wallet update failed");
        response.setResponseCode(ResponseCode.FAILED.value());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    response.setResponseMessage("Booking Confirmed and Wallet Updated Successfully");
    response.setResponseCode(ResponseCode.SUCCESS.value());
    return new ResponseEntity<>(response, HttpStatus.OK);
}


	private int convertRupeesToPaisa(BigDecimal rupees) {
		return rupees.multiply(new BigDecimal(100)).intValue();
	}

	private String generateUniqueRefId() {
		return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
	}
}
