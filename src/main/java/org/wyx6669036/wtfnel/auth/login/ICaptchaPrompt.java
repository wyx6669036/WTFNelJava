package org.wyx6669036.wtfnel.auth.login;

import java.util.List;

/**
 * Asks the user to solve a 4399 captcha. Coordinates are in the original image's pixels.
 *
 * <p>Port of WtfNel.Core/Auth/Login/CaptchaPrompt.cs (the interface half).</p>
 */
public interface ICaptchaPrompt {

    /** Text captcha: returns the characters shown in the image. */
    String image(byte[] image);

    /** Slider captcha: returns how far (in background pixels) the piece must move right. */
    int jigsaw(byte[] background, byte[] piece);

    /** Click captcha: returns the click points, ordered as the characters are listed in {@code text}. */
    List<Point> click(byte[] image, String text);

    /** A click point in the captcha image. */
    record Point(int x, int y) {
    }
}
