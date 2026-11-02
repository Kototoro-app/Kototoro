#!/usr/bin/env python3
"""Regression checks for partial black flashes; run directly with Python's standard library."""

import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "zoom_checker", Path(__file__).with_name("check-reader-zoom-animation.py")
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class PartialBlackFlashTests(unittest.TestCase):
    def setUp(self):
        row = bytes((200, 40, 40)) * checker.WIDTH
        self.loaded = row * checker.HEIGHT
        # A lost lower page surrounded by unchanged content, as in the device recording.
        self.partial = row * (checker.HEIGHT // 2) + bytes(len(self.loaded) // 2)
        self.black = bytes(len(self.loaded))

    def failures(self, frames):
        detector = checker.PartialBlackFlashDetector()
        return [result[0] for index, frame in enumerate(frames)
                if (result := detector.push(index, frame)) is not None and result[2]]

    def testTwoFramePartialReloadIsDetected(self):
        self.assertEqual(self.failures([self.loaded] * 20 + [self.partial] * 2 + [self.loaded] * 20), [20, 21])

    def testWholeFrameBlackFlashIsDetected(self):
        self.assertEqual(self.failures([self.loaded] * 20 + [self.black] + [self.loaded] * 20), [20])

    def testUnchangedContentDoesNotFail(self):
        self.assertEqual(self.failures([self.loaded] * 45), [])

    def testPersistentBlackMarginsDoNotFail(self):
        self.assertEqual(self.failures([self.partial] * 45), [])

    def testNavigationToAnotherPageDoesNotFail(self):
        different = bytes((40, 40, 200)) * (checker.WIDTH * checker.HEIGHT)
        self.assertEqual(self.failures([self.loaded] * 20 + [self.partial] * 2 + [different] * 20), [])


if __name__ == "__main__":
    unittest.main()
