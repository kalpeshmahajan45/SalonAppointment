package com.salon.booking.controller;

import java.util.ArrayList;
import java.util.List;

import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import com.salon.booking.dao.BookingDao;
import com.salon.booking.dto.*;
import com.salon.booking.entity.Booking;
import com.salon.booking.exception.BookingNotFoundException;
import com.salon.booking.external.SalonService;
import com.salon.booking.external.UserService;
import com.salon.booking.service.BookingService;
import com.salon.booking.utility.Constants.BookingStatus;
import com.salon.booking.utility.Constants.ResponseCode;
import com.salon.booking.utility.Helper;

@Transactional
@RestController
@RequestMapping("/api/book/salon")
public class BookingController {

    Logger LOG = LoggerFactory.getLogger(BookingController.class);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingDao bookingDao;

    @Autowired
    private SalonService salonService;

    @Autowired
    private UserService userService;

    // ===================== BOOKING VALIDATION =====================

    @PostMapping("/validate")
    public ResponseEntity<?> validateCustomerBooking(@RequestBody Booking booking) {

        BookingDetailDto response = new BookingDetailDto();

        if (booking == null || booking.getUserId() == 0 || booking.getSalonId() == 0) {
            response.setResponseCode(ResponseCode.FAILED.value());
            response.setResponseMessage("Invalid Booking Data");
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        Salon salon = salonService.fetchSalon(booking.getSalonId()).getSalon();
        User customer = userService.fetchUser(booking.getUserId()).getUser();

        booking.setSalonUserId(salon.getUserId());

        List<Booking> bookings = bookingDao
                .findByDateAndTimeSlotAndStatusAndSalonId(
                        booking.getDate(),
                        booking.getTimeSlot(),
                        BookingStatus.APPROVED.value(),
                        booking.getSalonId());

        if (!CollectionUtils.isEmpty(bookings)) {
            response.setResponseCode(ResponseCode.FAILED.value());
            response.setResponseMessage("Slot already booked");
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        booking.setStatus(BookingStatus.PENDING.value());
        booking.setBookingId(Helper.getAlphaNumericId());
        booking.setPrice(salon.getPricePerDay());
        booking.setCustomerFirstName(customer.getFirstName());
        booking.setCustomerLastName(customer.getLastName());
        booking.setCustomerContact(customer.getContact());
        booking.setCustomerEmailId(customer.getEmailId());

        Booking saved = bookingService.bookSalon(booking);

        response.setBooking(saved);
        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("Slot Available, Proceed to Payment");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ===================== CONFIRM BOOKING =====================
    @GetMapping("/fetch/status")
    public ResponseEntity<?> fetchAllBookingStatus() {
        List<String> statuses = new ArrayList<>();
        for (BookingStatus status : BookingStatus.values()) {
            statuses.add(status.value());
        }
        return new ResponseEntity<>(statuses, HttpStatus.OK);
    }
    
    @PostMapping("/")
    public ResponseEntity<?> confirmBooking(@RequestBody Booking booking) {

        Booking pendingBooking = bookingDao.findById(booking.getId()).orElse(null);

        if (pendingBooking == null) {
            throw new BookingNotFoundException();
        }

        pendingBooking.setStatus(BookingStatus.APPROVED.value());
        bookingService.bookSalon(pendingBooking);

        CommanApiResponse response = new CommanApiResponse();
        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("Booking Confirmed Successfully");

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ===================== CUSTOMER BOOKINGS =====================
    @PostMapping("/update-after-payment")
    public ResponseEntity<?> updateAfterPayment(@RequestBody Booking booking) {

        Booking updated = bookingService.updateBookingAfterPayment(
                booking.getBookingId()
        );

        CommanApiResponse response = new CommanApiResponse();

        if (updated == null) {
            response.setResponseCode(ResponseCode.FAILED.value());
            response.setResponseMessage("Booking not found");
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }

        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("Booking Approved");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    
    
    @GetMapping("/fetch")
    public ResponseEntity<?> fetchCustomerBookings(@RequestParam int userId) {

        BookingDetailDto response = new BookingDetailDto();
        List<BookingDto> bookings = new ArrayList<>();

        for (Booking booking : bookingService.getMyBookings(userId)) {

            BookingDto dto = mapBookingToDto(booking);
            bookings.add(dto);
        }

        response.setBookings(bookings);
        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("Customer Bookings Fetched");

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ===================== SALON MANAGER BOOKINGS (FIXED) =====================

    @GetMapping("/fetch/bookings")
    public ResponseEntity<?> fetchSalonBookings(@RequestParam int salonUserId) {

        BookingDetailDto response = new BookingDetailDto();
        List<BookingDto> bookings = new ArrayList<>();

        User salonUser = userService.fetchUser(salonUserId).getUser();
        int salonId = salonUser.getSalonId();

        for (Booking booking : bookingService.getMySalonBookings(salonId)) {

            BookingDto dto = mapBookingToDto(booking);
            bookings.add(dto);
        }

        response.setBookings(bookings);
        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("Salon Bookings Fetched");

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ===================== ADMIN ALL BOOKINGS =====================

    @GetMapping("/fetch/all")
    public ResponseEntity<?> fetchAllBookings() {

        BookingDetailDto response = new BookingDetailDto();
        List<BookingDto> bookings = new ArrayList<>();

        for (Booking booking : bookingService.getAllBookings()) {

            BookingDto dto = mapBookingToDto(booking);
            bookings.add(dto);
        }

        response.setBookings(bookings);
        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("All Bookings Fetched");

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ===================== UPDATE STATUS =====================

    @PostMapping("/update/status")
    public ResponseEntity<?> updateStatus(@RequestBody UpdateBookingStatusRequestDto request) {

        Booking booking = bookingService.getBookingById(request.getBookingId());
        booking.setStatus(request.getStatus());
        bookingService.bookSalon(booking);

        CommanApiResponse response = new CommanApiResponse();
        response.setResponseCode(ResponseCode.SUCCESS.value());
        response.setResponseMessage("Booking Status Updated");

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // ===================== COMMON MAPPER =====================

    private BookingDto mapBookingToDto(Booking booking) {

        BookingDto dto = new BookingDto();

        User customer = userService.fetchUser(booking.getUserId()).getUser();
        Salon salon = salonService.fetchSalon(booking.getSalonId()).getSalon();
        User salonUser = userService.fetchUser(salon.getUserId()).getUser();

        dto.setBookingId(booking.getBookingId());
        dto.setDate(booking.getDate());
        dto.setTimeSlot(booking.getTimeSlot());
        dto.setStatus(booking.getStatus());

        dto.setCustomerName(customer.getFirstName() + " " + customer.getLastName());
        dto.setCustomerContact(customer.getContact());

        dto.setSalonId(salon.getId());
        dto.setSalonName(salon.getName());
        dto.setSalonImage(salon.getImage1());
        dto.setSalonContact(salonUser.getContact());

        dto.setTotalAmount(String.valueOf(salon.getPricePerDay()));
        dto.setUserId(customer.getId());
        dto.setId(booking.getId());

        return dto;
    }
}
