import numpy as np
import scipy.signal


class FirFilter:
    def __init__(self, taps):
        self._taps = taps
        self._state = np.zeros(len(taps) - 1)

    def __call__(self, samples):
        filtered_samples, new_state = scipy.signal.lfilter(
            self._taps, 1.0, samples, zi=self._state
        )
        self._state = new_state
        return filtered_samples

    def reset(self):
        """
        Clears the delay line.

        Call this between captures that are not consecutive samples of
        one stream, so that a record does not begin with the tail of an
        unrelated one.
        """
        self._state = np.zeros(len(self._taps) - 1)

    def __len__(self):
        return len(self._taps)


def make_moving_average_filter(window_length):
    """
    Makes a moving average filter.

    Args:
        window_length (int): length of window

    Returns:
        FirFilter: the filter
    """
    taps = np.ones(window_length) / window_length
    return FirFilter(taps)


def make_fir_lowpass_filter(sample_frequency, cutoff_frequency, numtaps=101):
    """
    Makes a linear-phase FIR low-pass filter.

    Args:
        sample_frequency (int): The sampling rate of the audio data (Hz).
        cutoff_frequency (float): The desired cutoff frequency (Hz).
        numtaps (int): The number of taps (coefficients) in the filter.
                       Must be odd.

    Returns:
        FirFilter: the filter
    """
    assert numtaps % 2 != 0
    nyquist = sample_frequency / 2.0
    normalized_cutoff = cutoff_frequency / nyquist
    taps = scipy.signal.firwin(numtaps, normalized_cutoff, pass_zero="lowpass")
    return FirFilter(taps)


def make_fir_highpass_filter(sample_frequency, cutoff_frequency, numtaps=101):
    """
    Makes a linear-phase FIR high-pass filter.

    Args:
        sample_frequency (int): The sampling rate of the audio data (Hz).
        cutoff_frequency (float): The desired cutoff frequency (Hz).
        numtaps (int): The number of taps (coefficients) in the filter.
                       Must be odd.

    Returns:
        FirFilter: the filter
    """
    assert numtaps % 2 != 0
    nyquist = sample_frequency / 2.0
    normalized_cutoff = cutoff_frequency / nyquist
    taps = scipy.signal.firwin(numtaps, normalized_cutoff, pass_zero="highpass")
    return FirFilter(taps)


def make_fir_bandpass_filter(
    sample_frequency, lowcut_frequency, highcut_frequency, numtaps=101
):
    """
    Makes a linear-phase FIR band-pass filter.

    Args:
        sample_frequency (int): The sampling rate of the audio data (Hz).
        lowcut_frequency (float): The lower cutoff frequency (Hz).
        highcut_frequency (float): The upper cutoff frequency (Hz).
        numtaps (int): The number of taps (coefficients) in the filter.
                       Must be odd.

    Returns:
        FirFilter: the filter
    """
    assert numtaps % 2 != 0
    nyquist = sample_frequency / 2.0
    normalized_lowcut = lowcut_frequency / nyquist
    normalized_highcut = highcut_frequency / nyquist
    taps = scipy.signal.firwin(
        numtaps, [normalized_lowcut, normalized_highcut], pass_zero="bandpass"
    )
    return FirFilter(taps)


def make_audio_filter(sample_frequency, hpf_cutoff, lpf_cutoff, numtaps=101):
    """
    Makes the filter implied by a pair of optional cutoffs.

    The cutoffs are named for the filter each one alone would produce, so
    hpf_cutoff is the lower edge of the passband and lpf_cutoff the
    upper.  Both together give a bandpass; one gives that one filter;
    neither gives no filter.

    Args:
        sample_frequency (float): sample rate of the signal (Hz)
        hpf_cutoff (float): highpass cutoff (Hz), or None
        lpf_cutoff (float): lowpass cutoff (Hz), or None
        numtaps (int): number of taps.  Must be odd.

    Returns:
        FirFilter: the filter, or None if both cutoffs are None
    """
    if hpf_cutoff is not None and lpf_cutoff is not None:
        if hpf_cutoff >= lpf_cutoff:
            raise ValueError(
                f"highpass cutoff ({hpf_cutoff} Hz) must be below the "
                f"lowpass cutoff ({lpf_cutoff} Hz)"
            )
        return make_fir_bandpass_filter(
            sample_frequency, hpf_cutoff, lpf_cutoff, numtaps
        )
    if lpf_cutoff is not None:
        return make_fir_lowpass_filter(sample_frequency, lpf_cutoff, numtaps)
    if hpf_cutoff is not None:
        return make_fir_highpass_filter(sample_frequency, hpf_cutoff, numtaps)
    return None
