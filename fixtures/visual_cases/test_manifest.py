"""Pytest tests for visual_cases manifest validation"""

import pytest
from datetime import datetime
from pathlib import Path
from jsonschema import validate, ValidationError, FormatChecker


class TestManifestSchema:
    """Test manifest JSON schema validation"""
    
    def test_manifest_is_valid_json(self, manifest_data):
        """Manifest should be parseable JSON"""
        assert isinstance(manifest_data, dict)
    
    def test_manifest_matches_schema(self, manifest_data, schema_data):
        """Manifest should conform to schema"""
        # Should not raise ValidationError
        validate(instance=manifest_data, schema=schema_data, format_checker=FormatChecker())
    
    def test_manifest_has_required_top_level_fields(self, manifest_data):
        """Manifest should have required top-level fields"""
        assert 'version' in manifest_data
        assert 'created_at' in manifest_data
        assert 'commit_hash' in manifest_data
        assert 'cases' in manifest_data


class TestCaseCount:
    """Test case count and completeness"""
    
    def test_exactly_twelve_cases(self, manifest_data):
        """Manifest should contain exactly 12 cases"""
        assert len(manifest_data['cases']) == 12
    
    def test_all_required_categories_present(self, manifest_data):
        """All four categories should be represented"""
        categories_found = set()
        for case in manifest_data['cases']:
            categories_found.add(case['category'])
        
        required_categories = {'food', 'health', 'watch', 'negative'}
        assert required_categories == categories_found
    
    def test_food_category_coverage(self, manifest_data):
        """Food category should have F01-F04"""
        food_ids = {case['id'] for case in manifest_data['cases'] if case['category'] == 'food'}
        assert food_ids == {'F01', 'F02', 'F03', 'F04'}
    
    def test_health_category_coverage(self, manifest_data):
        """Health category should have H01-H04"""
        health_ids = {case['id'] for case in manifest_data['cases'] if case['category'] == 'health'}
        assert health_ids == {'H01', 'H02', 'H03', 'H04'}
    
    def test_watch_category_coverage(self, manifest_data):
        """Watch category should have W01-W02"""
        watch_ids = {case['id'] for case in manifest_data['cases'] if case['category'] == 'watch'}
        assert watch_ids == {'W01', 'W02'}
    
    def test_negative_category_coverage(self, manifest_data):
        """Negative category should have N01-N02"""
        negative_ids = {case['id'] for case in manifest_data['cases'] if case['category'] == 'negative'}
        assert negative_ids == {'N01', 'N02'}


class TestCaseUniqueness:
    """Test for duplicate cases"""
    
    def test_all_ids_unique(self, manifest_data):
        """All case IDs should be unique"""
        ids = [case['id'] for case in manifest_data['cases']]
        assert len(ids) == len(set(ids)), f"Duplicate IDs found: {ids}"
    
    def test_all_paths_unique(self, manifest_data):
        """All file paths should be unique"""
        paths = [case['path'] for case in manifest_data['cases']]
        assert len(paths) == len(set(paths)), f"Duplicate paths found: {paths}"


class TestFileExistence:
    """Test that referenced files exist"""
    
    def test_all_files_exist(self, manifest_data, base_dir):
        """All referenced image files should exist"""
        missing_files = []
        for case in manifest_data['cases']:
            file_path = base_dir / case['path']
            if not file_path.exists():
                missing_files.append(case['path'])
        
        assert not missing_files, f"Missing files: {missing_files}"
    
    def test_all_paths_are_files_not_directories(self, manifest_data, base_dir):
        """All referenced paths should be files"""
        non_files = []
        for case in manifest_data['cases']:
            file_path = base_dir / case['path']
            if file_path.exists() and not file_path.is_file():
                non_files.append(case['path'])
        
        assert not non_files, f"Paths are not files: {non_files}"


class TestDateValidation:
    """Test date and time formats"""
    
    def test_all_dates_valid_iso8601(self, manifest_data):
        """All date strings should be valid ISO8601"""
        invalid_dates = []
        for case in manifest_data['cases']:
            date_str = case.get('datetime', {}).get('date')
            if date_str:
                try:
                    datetime.fromisoformat(date_str)
                except ValueError:
                    invalid_dates.append((case['id'], date_str))
        
        assert not invalid_dates, f"Invalid dates: {invalid_dates}"
    
    def test_all_times_valid_hhmm(self, manifest_data):
        """All time strings should be valid HH:MM format"""
        invalid_times = []
        for case in manifest_data['cases']:
            time_str = case.get('datetime', {}).get('time')
            if time_str:
                try:
                    parts = time_str.split(':')
                    assert len(parts) == 2, f"Expected HH:MM, got {time_str}"
                    hour, minute = int(parts[0]), int(parts[1])
                    assert 0 <= hour <= 23, f"Hour out of range: {hour}"
                    assert 0 <= minute <= 59, f"Minute out of range: {minute}"
                except (ValueError, AssertionError) as e:
                    invalid_times.append((case['id'], time_str, str(e)))
        
        assert not invalid_times, f"Invalid times: {invalid_times}"
    
    def test_date_range_spans_multiple_days(self, manifest_data):
        """Dates should span multiple days for proper coverage"""
        dates = []
        for case in manifest_data['cases']:
            date_str = case.get('datetime', {}).get('date')
            if date_str:
                dates.append(datetime.fromisoformat(date_str))
        
        if dates:
            min_date = min(dates)
            max_date = max(dates)
            date_range_days = (max_date - min_date).days
            assert date_range_days >= 3, f"Date range too small: {date_range_days} days"


class TestExpectedActions:
    """Test expected_action field values"""
    
    def test_all_expected_actions_valid(self, manifest_data):
        """expected_action should be one of: draft, ask, refuse"""
        valid_actions = {'draft', 'ask', 'refuse'}
        invalid_actions = []
        for case in manifest_data['cases']:
            action = case.get('expected_action')
            if action not in valid_actions:
                invalid_actions.append((case['id'], action))
        
        assert not invalid_actions, f"Invalid actions: {invalid_actions}"
    
    def test_refuse_cases_have_reason(self, manifest_data):
        """Cases with expected_action='refuse' should have refuse_reason"""
        refuse_without_reason = []
        for case in manifest_data['cases']:
            if case.get('expected_action') == 'refuse':
                if not case.get('refuse_reason'):
                    refuse_without_reason.append(case['id'])
        
        assert not refuse_without_reason, f"Refuse cases without reason: {refuse_without_reason}"
    
    def test_ask_cases_have_clarification(self, manifest_data):
        """Cases with expected_action='ask' should have ask_clarification"""
        ask_without_clarification = []
        for case in manifest_data['cases']:
            if case.get('expected_action') == 'ask':
                if not case.get('ask_clarification'):
                    ask_without_clarification.append(case['id'])
        
        assert not ask_without_clarification, f"Ask cases without clarification: {ask_without_clarification}"


class TestSourceMetadata:
    """Test source and attribution metadata"""
    
    def test_all_cases_have_source_info(self, manifest_data):
        """All cases should have source, license, and attribution"""
        incomplete_sources = []
        for case in manifest_data['cases']:
            source = case.get('source', {})
            if not all(k in source for k in ['origin', 'license', 'attribution']):
                incomplete_sources.append(case['id'])
        
        assert not incomplete_sources, f"Incomplete source info: {incomplete_sources}"
    
    def test_all_cases_marked_no_personal_data(self, manifest_data):
        """All cases should be marked as no personal data"""
        with_personal_data = []
        for case in manifest_data['cases']:
            if not case.get('personal_data_check', False):
                with_personal_data.append(case['id'])
        
        assert not with_personal_data, f"Cases with potential personal data: {with_personal_data}"
