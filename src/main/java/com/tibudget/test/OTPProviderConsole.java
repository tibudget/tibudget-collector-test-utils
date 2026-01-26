package com.tibudget.test;

import com.tibudget.api.OTPProvider;

import java.util.Scanner;

public class OTPProviderConsole implements OTPProvider {

    private final Scanner scanner;

    /**
     * Default constructor initializing the scanner.
     */
    public OTPProviderConsole() {
        this.scanner = new Scanner(System.in);
    }

    @Override
    public String getCode(Channel channel, String keyword, String pattern, String prompt) {
        if (prompt != null && !prompt.isEmpty()) {
            System.out.println(prompt);
        }
        System.out.print("Please enter the OTP received via " + channel + " (matching pattern: " + pattern + ") : ");
        String otp = scanner.nextLine();

        // Validate OTP format
        if (otp.matches(pattern)) {
            return otp;
        } else {
            System.out.println("Invalid OTP format. Expected: " + pattern);
            return null;
        }
    }
}
