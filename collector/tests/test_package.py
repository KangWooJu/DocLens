import collector


def test_package_imports() -> None:
    assert collector.__doc__ == "DocLens document collector."
