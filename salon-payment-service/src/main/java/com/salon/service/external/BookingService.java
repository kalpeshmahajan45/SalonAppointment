package com.salon.service.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.salon.service.dto.Booking;
import com.salon.service.dto.CommanApiResponse;

@Component
@FeignClient(
    name = "salon-booking-service",
    url = "http://localhost:8080/api/book/salon"
)
public interface BookingService {

    // ❌ OLD – booking create (before payment)
    @PostMapping("/")
    CommanApiResponse addCustomerBooking(@RequestBody Booking booking);

    // ✅ NEW – booking update AFTER payment success
    @PostMapping("/update-after-payment")
    CommanApiResponse updateBookingAfterPayment(@RequestBody Booking booking);
}
