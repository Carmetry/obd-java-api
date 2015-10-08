package com.github.pires.obd.utils;

import com.github.pires.obd.commands.protocol.AvailablePidsCommand;

public abstract class CommandAvailabilityHelper {

    public static byte[] digestAvailabilityString(final String string) {
        //The string must have 8*n characters, n being an integer
        if (string.length() % 8 != 0) {
            throw new IllegalArgumentException("Invalid length for Availability String supplied: " + string);
        }

        //Each two characters of the string will be digested into one byte, thus the resulting array will 
        //have half the elements the string has
        byte[] availabilityArray = new byte[string.length() / 2];

        for (int i = 0; i < availabilityArray.length; ++i) {
            //First character is more significant
            availabilityArray[0] = (byte) (16 * parseHexChar(string.charAt(i)) + parseHexChar(string.charAt(i + 1)));
        }

        return availabilityArray;
    }

    private static byte parseHexChar(char hexChar) {
        switch (hexChar) {
            case '0':
                return 0;

            case '1':
                return 1;

            case '2':
                return 2;

            case '3':
                return 3;

            case '4':
                return 4;

            case '5':
                return 5;

            case '6':
                return 6;

            case '7':
                return 7;

            case '8':
                return 8;

            case '9':
                return 9;

            case 'A':
                return 10;

            case 'B':
                return 11;

            case 'C':
                return 12;

            case 'D':
                return 13;

            case 'E':
                return 14;

            case 'F':
                return 15;

            default:
                throw new IllegalArgumentException("Invalid character [" + hexChar + "] supplied");
        }
    }
    
    public static boolean isAvailable(AvailablePidsCommand command, String availabilityString) {
        return isAvailable(command, digestAvailabilityString(availabilityString));
    }

    public static boolean isAvailable(AvailablePidsCommand command, byte[] availabilityArray) {
        //Which byte from the array contains the info we want?
        //Ignore first 3 characters from the command string, which are 2 for PID mode and 1 whitespace
        String pidNumber = command.getCommand().substring(3);
        byte cmdNumber = Byte.parseByte(pidNumber, 16);
        int arrayIndex = cmdNumber / 8;

        //Substract 8 from cmdNumber until we have it in the 1-8 range
        while (cmdNumber > 8) {
            cmdNumber -= 8;
        }

        byte requestedAvailability;

        switch (cmdNumber) {
            case 1:
                requestedAvailability = (byte) 128;
                break;
            case 2:
                requestedAvailability = (byte) 64;
                break;
            case 3:
                requestedAvailability = (byte) 32;
                break;
            case 4:
                requestedAvailability = (byte) 16;
                break;
            case 5:
                requestedAvailability = (byte) 8;
                break;
            case 6:
                requestedAvailability = (byte) 4;
                break;
            case 7:
                requestedAvailability = (byte) 2;
                break;
            case 8:
                requestedAvailability = (byte) 1;
                break;
            default:
                throw new RuntimeException("This is not supposed to happen.");
        }
        
        return requestedAvailability == (requestedAvailability & availabilityArray[arrayIndex]);
    }
}
