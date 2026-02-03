package com.salon.booking.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.salon.booking.dao.BookingDao;
import com.salon.booking.entity.Booking;
import com.salon.booking.utility.Constants.BookingStatus;

@Service
public class BookingService {

    @Autowired
    private BookingDao bookingDao;

    // ✅ CREATE BOOKING (DEFAULT = PENDING)
    public Booking bookSalon(Booking booking) {
        booking.setStatus("PENDING"); // IMPORTANT
        return bookingDao.save(booking);
    }

    public List<Booking> getAllBookings() {
        return bookingDao.findAll();
    }

    public List<Booking> getMyBookings(int userId) {
        return bookingDao.findByUserId(userId);
    }

    public List<Booking> getMySalonBookings(int salonId) {
        return bookingDao.findBySalonId(salonId);
    }

    public Booking getBookingById(int bookingId) {
        return bookingDao.findById(bookingId).orElse(null);
    }

    // =====================================================
    // ✅ PAYMENT SUCCESS → CONFIRM BOOKING
    // =====================================================
    public Booking updateBookingAfterPayment(String bookingId) {
        Booking existingBooking = bookingDao.findByBookingId(bookingId);
        if (existingBooking == null) return null;

        existingBooking.setStatus(BookingStatus.APPROVED.value());
        return bookingDao.save(existingBooking);
    }

}
