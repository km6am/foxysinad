#
# SINAD measurement.
#

import numpy as np

from vendored import pysnr


def measure(samples, sample_frequency):
    """
    Measures the SINAD of a record.

    Two definitions are in use and both are standard.  Radio work, and
    the 12 dB SINAD sensitivity figure in particular, means

        (S+N+D)/(N+D)

    which is never below 0 dB: a vanishing tone approaches 1, not 0.
    ADC and DAC work means S/(N+D), the fundamental over the residual,
    which can go negative.  That is what MATLAB's sinad() returns, and
    pysnr follows MATLAB, so it is what the vendored code returns.

    This is a receiver sensitivity meter, so we want the first, and
    converting is exact: S/(N+D) + 1 = (S+N+D)/(N+D).  The difference
    only shows up at low SINAD -- +3.01 dB at 0, +0.27 dB at 12,
    +0.04 dB at 20 -- so readings well above the sensitivity point
    barely move.  It is also why the Keithley 2015, which measures the
    radio definition, bottoms out near 0 dB where pysnr goes to -4.

    Args:
        samples (numpy.ndarray): the record, as a 1-D array
        sample_frequency (float): sample rate of the record (Hz)

    Returns:
        (float, float): the SINAD (dB) and the total noise-plus-
                        distortion power (dB)
    """
    (snr_dB, noise_dB) = pysnr.sinad_signal(samples, fs=sample_frequency)
    return (10.0 * np.log10(1.0 + 10.0 ** (snr_dB / 10.0)), noise_dB)
